# MCP 工具统一纳管与标的属性全维检索架构设计

- **日期**：2026-10-20
- **模块范围**：`copilot-domain`, `copilot-data-engine`, `copilot-agent-tools`, `copilot-mcp-service`, `copilot-agent-core`
- **状态**：Approved

---

## 1. 背景与目标

在 FinancialCopilot 投研系统中，先前已经实现了私有基金池 (`fund_pool`) 与股票自选股 (`stock_watchlist`) 的底层增删改查业务能力。然而在真实用户场景中存在两个关键短板：
1. **入参上下文缺失**：用户常以口语化表达（如“把茅台加到自选”、“把易方达蓝筹加进基金池”），并不提供 6 位纯数字代码或当前现价、行业等信息。Agent 缺少根据代码或模糊名称查询标的完整量化属性的专职工具，导致无法有效组装 `add_to_stock_watchlist` / `add_to_fund_pool` 的必要字段。
2. **MCP 生态统一纳管与协议解耦**：系统设有独立的 WebFlux MCP 2024-11-05 服务端 (`copilot-mcp-service`，端口 8085)。基金池管理、自选股管理及属性查询等业务能力应当统一注册至 MCP 服务端，使外部标准 MCP Client 及内部 Agent 均能通过同一协议调度；同时内部 Agent 需支持自动从认证上下文提取 `userId` 通过 HTTP Header `X-User-Id` 传递，并支持 MCP 优先与本地直调平滑降级。

---

## 2. 总体架构与数据流

```
[AgentScope 智能体 / 外部 MCP 客户端]
                 │
                 ▼
[ReactiveMcpClient (copilot-agent-core)] ──(携带 X-User-Id 请求头)──► [copilot-mcp-service (端口 8085)]
                 │ (网络不可用时本地透明平滑降级)                                  │
                 ▼                                                               │
[ContextualToolkitFactory 本地委托] ◄─────────────────────────────────────────────┘
                 │
                 ├─ StockProfileTool / FundProfileTool (标的详情检索)
                 ├─ StockWatchlistTool / FundPoolTool (自选股与基金池增删改查)
                 │
                 ▼
[copilot-data-engine / copilot-domain] (MyBatis-Plus 持久层与领域 SPI)
```

### 业务闭环时序

1. **标的识别与补全**：
   - 用户提问：“把贵州茅台加到自选股，目标价1800”；
   - Agent 识别到需调用 `add_to_stock_watchlist`，但缺乏标准化代码与行情，首先调用 `query_stock_profile(query="贵州茅台")`；
   - 工具返回：代码 `600519`，全称 `贵州茅台`，行业 `食品饮料`，最新价 `1680.00`，PE `28.5`；
2. **组合变更执行**：
   - Agent 获取准确属性后，调用 `add_to_stock_watchlist(stockCode="600519", stockName="贵州茅台", groupName="默认", targetPrice=1800.0, notes="行业龙头，估值合理")`；
3. **协议流转与安全注入**：
   - Agent 核心调度优先通过 `ReactiveMcpClient` 路由至 MCP 服务端（端口 8085），并在 HTTP 请求头上透传已认证用户的 `X-User-Id`；
   - 若 MCP 服务不可达或超时，本地委托透明捕获并无缝回退至本地 Spring Bean 直接执行，保障高可用。

---

## 3. 详细设计与接口契约

### 3.1 领域层与持久层扩展 (`copilot-domain` & `copilot-data-engine`)

- **`FundDataPort` / `DatabaseFundDataAdapter`**：
  ```java
  /**
   * 按代码或中文名称模糊检索基金候选标的
   *
   * @param keyword 基金代码或名称关键词 (如 "005827" 或 "易方达蓝筹")
   * @param limit   最大返回数 (默认 5)
   * @return 匹配的基金实体列表
   */
  List<FundInfo> searchFunds(String keyword, int limit);
  ```
  实现方式：MyBatis-Plus `LambdaQueryWrapper<FundInfoPO>` 匹配 `fund_code LIKE %kw% OR fund_name LIKE %kw%`。

- **`StockDataPort` / `DatabaseStockDataAdapter`**：
  ```java
  /**
   * 按代码或中文名称模糊检索股票候选标的
   *
   * @param keyword 股票代码或名称关键词 (如 "600519" 或 "茅台")
   * @param limit   最大返回数 (默认 5)
   * @return 匹配的股票实体列表
   */
  List<StockInfo> searchStocks(String keyword, int limit);
  ```
  实现方式：MyBatis-Plus `LambdaQueryWrapper<StockInfoPO>` 匹配 `stock_code LIKE %kw% OR stock_name LIKE %kw%`。

---

### 3.2 工具层实现 (`copilot-agent-tools`)

#### 1. `StockProfileTool`
- **类全称**：`com.financial.copilot.agent.tools.stock.StockProfileTool`
- **方法**：`public String getStockProfile(String query)`
- **注解**：`@Tool(name = "query_stock_profile", description = "查询股票全维档案与实时估值(支持6位代码或中文模糊名称)", readOnly = true)`
- **逻辑**：
  1. 若入参满足 `^\d{6}$`，直接通过 `StockDataPort.getStockByCode(code)` 精确检索；
  2. 否则通过 `StockDataPort.searchStocks(query, 5)` 模糊检索候选，取首选标的；
  3. 联动调用 `StockDataPort.getStockMetrics(code)`，组装当前市价、PE(TTM)、PB、ROE、股息率、申万一级行业等；
  4. 序列化为结构化 JSON 返回：
     ```json
     {
       "status": "SUCCESS",
       "stockCode": "600519",
       "stockName": "贵州茅台",
       "exchange": "SH",
       "primarySector": "食品饮料",
       "latestPrice": 1680.00,
       "peTtm": 28.5,
       "pb": 8.2,
       "roe": 31.5,
       "dividendYield": 2.8,
       "marketCapBillion": 2110.0
     }
     ```

#### 2. `FundProfileTool`
- **类全称**：`com.financial.copilot.agent.tools.fund.FundProfileTool`
- **方法**：`public String getFundProfile(String query)`
- **注解**：`@Tool(name = "query_fund_profile", description = "查询基金全维档案与关键业绩(支持6位代码或中文模糊名称)", readOnly = true)`
- **逻辑**：
  1. 若入参满足 `^\d{6}$`，直接通过 `FundDataPort.getFundByCode(code)` 精确检索；
  2. 否则通过 `FundDataPort.searchFunds(query, 5)` 模糊检索候选，取首选标的；
  3. 组装基金类型、管理机构、基金经理、最新单位净值、当前规模及业绩基准；
  4. 序列化为结构化 JSON 返回：
     ```json
     {
       "status": "SUCCESS",
       "fundCode": "005827",
       "fundName": "易方达蓝筹精选混合",
       "fundType": "混合型",
       "managementCompany": "易方达基金",
       "managerName": "张坤",
       "currentScaleBillion": 420.5,
       "latestNav": 1.7820,
       "trackingBenchmark": "MSCI中国A股指数收益率*45%+..."
     }
     ```

---

### 3.3 MCP 服务端注册管理 (`copilot-mcp-service`)

#### 1. 响应式用户上下文传递 (`McpUserContext`)
- 位于 `com.financial.copilot.mcp.context.McpUserContext`；
- 在 `McpSseController` 中拦截 POST `/messages` 上行请求，提取 `@RequestHeader(value = "X-User-Id", required = false) String userIdHeader`；
- 通过 Reactor 上下文注入：`.contextWrite(Context.of("MCP_USER_ID", Long.parseLong(userIdHeader)))`。

#### 2. `FinancialToolsBinder` 注册列表拓展
在端口 8085 的全局 MCP 注册表挂载新工具：
1. `query_stock_profile` 与 `query_fund_profile`（只读，直接调用相应 Profile 工具）；
2. 基金池管理工具（从 Reactor Context 读取 `userId`，纯净参数调用 `FundPoolTool`）：
   - `add_to_fund_pool(fundCode, fundName, poolType, targetWeight, reason, tags)`
   - `update_fund_pool(fundCode, poolType, targetWeight, reason)`
   - `remove_from_fund_pool(fundCode, poolType)`
   - `clear_fund_pool(poolType)`
   - `query_fund_pool(poolType)`
3. 自选股管理工具（从 Reactor Context 读取 `userId`，纯净参数调用 `StockWatchlistTool`）：
   - `add_to_stock_watchlist(stockCode, stockName, groupName, targetPrice, notes, priority)`
   - `update_stock_watchlist(stockCode, groupName, targetPrice, notes)`
   - `remove_from_stock_watchlist(stockCode, groupName)`
   - `clear_stock_watchlist(groupName)`
   - `query_stock_watchlist(groupName)`

---

### 3.4 Agent 核心协同与降级编排 (`copilot-agent-core`)

#### 1. `ReactiveMcpClient` 拓展
- 新增支持携带 `userId` 的重载方法：
  - `Mono<McpCallResult> callTool(String toolName, Map<String, Object> arguments, @Nullable Long userId)`
  - `String callToolSync(String toolName, Map<String, Object> arguments, @Nullable Long userId)`
- 执行 WebClient 请求时，若 `userId != null`，添加请求头 `.header("X-User-Id", String.valueOf(userId))`。

#### 2. `ContextualToolkitFactory` 双层装配
- 在各个 Tool 委托类中（`FundPoolToolDelegate`, `StockWatchlistToolDelegate`, `StockProfileToolDelegate`, `FundProfileToolDelegate`）：
  - 若 `mcpClient != null && mcpClient.isEnabled()`，优先调用 `mcpClient.callToolSync(name, args, userId)`；
  - 若远程调用失败（超时或异常），打印 warn 日志后自动降级到本地注入的本地工具 Bean 执行。
- 在 `AgentRoleProperties` 默认工具清单中：
  - `FUND_ANALYZER`, `FUND_SCREENER` 添加 `"query_fund_profile"`；
  - `STOCK_ANALYZER`, `STOCK_SCREENER` 添加 `"query_stock_profile"`。
- 在 `filterToolsByIntent` 意图剪枝规则中：
  - `FUND_SCREENING`, `FUND_DIAGNOSIS`, `ASSET_ALLOCATION` 保留 `"query_fund_profile"`；
  - `STOCK_RESEARCH`, `ASSET_ALLOCATION` 保留 `"query_stock_profile"`。

---

## 4. 测试与验证策略

1. **单元测试**：
   - `StockProfileToolTest` & `FundProfileToolTest`：测试代码直查与中文模糊检索分支；
   - `DatabaseFundDataAdapterTest` & `DatabaseStockDataAdapterTest`：测试 `searchFunds` 与 `searchStocks` 的 SQL/模糊查询；
   - `FinancialToolsBinderTest`：测试 MCP 工具挂载数量与 `McpUserContext` 提取；
   - `FundStockPoolToolkitTest` & `ContextualToolkitFactoryTest`：验证 MCP 优先与本地透明降级。
2. **全模块回归测试**：
   - `copilot-domain`, `copilot-data-engine`, `copilot-agent-tools`, `copilot-agent-core`, `copilot-mcp-service`, `copilot-app` 全部执行 `mvn test` 保证 0 failure。
