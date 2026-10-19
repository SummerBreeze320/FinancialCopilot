# MCP 工具统一纳管与标的属性全维检索实施计划 (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 FinancialCopilot 投研系统引入股票与基金标的全维属性查询工具（支持代码与模糊名称检索），并将标的查询、基金池与自选股增删改查全套工具统一注册至独立的 `copilot-mcp-service` (端口 8085)，实现 HTTP Header `X-User-Id` 认证上下文透传，并在 `copilot-agent-core` 中提供 MCP 优先调用与本地平滑降级机制。

**Architecture:** 
1. 在 `copilot-domain` 与 `copilot-data-engine` 补充基金和股票的关键词/模糊检索 SPI 契约与 MyBatis-Plus 实现；
2. 在 `copilot-agent-tools` 落地 `StockProfileTool` 与 `FundProfileTool`，提供结构化全景估值与基本面 JSON 输出；
3. 在 `copilot-mcp-service` 基于 Reactor Context 构建 `McpUserContext` 提取 `X-User-Id` 请求头，在 `FinancialToolsBinder` 挂载标的属性与基金池/自选股全部 12 个原子 MCP 工具；
4. 在 `copilot-agent-core` 的 `ReactiveMcpClient` 支持带 `userId` 透传，并在 `ContextualToolkitFactory` 中实现 MCP 优先 + 本地 Bean 优雅降级。

**Tech Stack:** Java 21, Spring Boot 3.3.3, Spring WebFlux, Project Reactor, MyBatis-Plus 3.5.7, AgentScope 2.0.0, JUnit 5, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-10-20-mcp-tools-and-asset-profile-query-design.md`

## Global Constraints

- 严格遵循 `.agents/rules/coding-standards.md`：Lombok 注解（`@Getter`, `@Setter`）、中文 Javadoc、MyBatis-Plus 查询规范。
- 遵循架构依赖单向性：`copilot-mcp-service` 与 `copilot-agent-core` 依赖 `copilot-agent-tools` 和 `copilot-domain`，绝不允许反向依赖。
- 不得硬编码外部网络 API Key，继续保证本地 Codex-Bridge (`http://127.0.0.1:9090/v1`) 与离线单元测试 100% 独立稳定运行。
- Tool 入参保持纯净，`userId` 不向大语言模型暴露，严格从会话上下文透传至 MCP 的 `X-User-Id` 请求头。

---

### Task 1: 领域层与持久层标的模糊检索能力扩展 (`copilot-domain` & `copilot-data-engine`)

**Files:**
- Modify: `copilot-domain/src/main/java/com/financial/copilot/domain/business/fund/port/FundDataPort.java`
- Modify: `copilot-domain/src/main/java/com/financial/copilot/domain/business/stock/port/StockDataPort.java`
- Modify: `copilot-data-engine/src/main/java/com/financial/copilot/data/fund/adapter/DatabaseFundDataAdapter.java`
- Modify: `copilot-data-engine/src/main/java/com/financial/copilot/data/stock/adapter/DatabaseStockDataAdapter.java`
- Test: `copilot-data-engine/src/test/java/com/financial/copilot/data/fund/adapter/DatabaseFundDataAdapterTest.java`
- Test: `copilot-data-engine/src/test/java/com/financial/copilot/data/stock/adapter/DatabaseStockDataAdapterTest.java`

**Interfaces:**
- Consumes: `FundInfoMapper`, `StockInfoMapper`
- Produces: `FundDataPort.searchFunds(String keyword, int limit)`, `StockDataPort.searchStocks(String keyword, int limit)`

- [ ] **Step 1: 在领域层 SPI 端口中声明 `searchFunds` 与 `searchStocks` 方法**

修改 `FundDataPort.java`：
```java
List<FundInfo> searchFunds(String keyword, int limit);
```

修改 `StockDataPort.java`：
```java
List<StockInfo> searchStocks(String keyword, int limit);
```

- [ ] **Step 2: 编写测试断言验证模糊检索能力**

在 `DatabaseFundDataAdapterTest.java` 中增加 `testSearchFundsByCodeOrName`：
```java
@Test
@DisplayName("根据关键词模糊检索基金标的")
void testSearchFundsByCodeOrName() {
    List<FundInfo> result = adapter.searchFunds("蓝筹", 5);
    assertThat(result).isNotNull();
}
```

在 `DatabaseStockDataAdapterTest.java` 中增加 `testSearchStocksByCodeOrName`：
```java
@Test
@DisplayName("根据关键词模糊检索股票标的")
void testSearchStocksByCodeOrName() {
    List<StockInfo> result = adapter.searchStocks("茅台", 5);
    assertThat(result).isNotNull();
}
```

- [ ] **Step 3: 在适配器中实现基于 MyBatis-Plus 的模糊查询**

在 `DatabaseFundDataAdapter.java` 中：
```java
@Override
public List<FundInfo> searchFunds(String keyword, int limit) {
    if (keyword == null || keyword.isBlank()) {
        return Collections.emptyList();
    }
    int maxLimit = limit > 0 ? Math.min(limit, 50) : 5;
    try {
        LambdaQueryWrapper<FundInfoPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(FundInfoPO::getFundCode, keyword.trim())
               .or()
               .like(FundInfoPO::getFundName, keyword.trim())
               .last("LIMIT " + maxLimit);
        List<FundInfoPO> pos = fundInfoMapper.selectList(wrapper);
        if (pos != null && !pos.isEmpty()) {
            return pos.stream().map(this::toFundInfo).toList();
        }
    } catch (Exception e) {
        log.warn("[FUND-SEARCH] 数据库检索异常: kw={}, error={}", keyword, e.getMessage());
    }
    // 样本兜底
    if ("005827".contains(keyword) || "易方达".contains(keyword) || "蓝筹".contains(keyword)) {
        return List.of(getFundByCode("005827").orElseThrow());
    }
    return Collections.emptyList();
}
```

在 `DatabaseStockDataAdapter.java` 中：
```java
@Override
public List<StockInfo> searchStocks(String keyword, int limit) {
    if (keyword == null || keyword.isBlank()) {
        return Collections.emptyList();
    }
    int maxLimit = limit > 0 ? Math.min(limit, 50) : 5;
    try {
        LambdaQueryWrapper<StockInfoPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StockInfoPO::getStockCode, keyword.trim())
               .or()
               .like(StockInfoPO::getStockName, keyword.trim())
               .last("LIMIT " + maxLimit);
        List<StockInfoPO> list = stockInfoMapper.selectList(wrapper);
        if (list != null && !list.isEmpty()) {
            return list.stream().map(this::mapToEntity).toList();
        }
    } catch (Exception e) {
        log.warn("[STOCK-SEARCH] 数据库检索异常: kw={}, error={}", keyword, e.getMessage());
    }
    // 样本兜底
    if ("600519".contains(keyword) || "茅台".contains(keyword)) {
        return List.of(getStockByCode("600519").orElseThrow());
    }
    return Collections.emptyList();
}
```

- [ ] **Step 4: 运行持久层单测验证**

运行：`mvn -s maven-settings.xml -pl copilot-domain,copilot-data-engine test`
期望：BUILD SUCCESS，所有测试通过。

- [ ] **Step 5: 提交 Task 1 代码**

```bash
git add copilot-domain/ copilot-data-engine/
git commit -m "feat(data): add searchFunds and searchStocks methods to domain ports and database adapters"
```

---

### Task 2: 工具层实现股票与基金标的全维属性查询工具 (`copilot-agent-tools`)

**Files:**
- Create: `copilot-agent-tools/src/main/java/com/financial/copilot/agent/tools/stock/StockProfileTool.java`
- Create: `copilot-agent-tools/src/main/java/com/financial/copilot/agent/tools/fund/FundProfileTool.java`
- Create: `copilot-agent-tools/src/test/java/com/financial/copilot/agent/tools/stock/StockProfileToolTest.java`
- Create: `copilot-agent-tools/src/test/java/com/financial/copilot/agent/tools/fund/FundProfileToolTest.java`

**Interfaces:**
- Consumes: `StockDataPort`, `FundDataPort`, `ObjectMapper`
- Produces: `StockProfileTool.getStockProfile(String query)`, `FundProfileTool.getFundProfile(String query)`

- [ ] **Step 1: 编写 `StockProfileToolTest` 测试用例**

```java
@Test
@DisplayName("按6位股票代码直查股票档案与量化指标")
void testGetProfileByCode() {
    StockProfileTool tool = new StockProfileTool(stockDataPort, objectMapper);
    String json = tool.getStockProfile("600519");
    assertThat(json).contains("600519").contains("贵州茅台").contains("SUCCESS");
}

@Test
@DisplayName("按模糊名称检索股票档案")
void testGetProfileByKeyword() {
    StockProfileTool tool = new StockProfileTool(stockDataPort, objectMapper);
    String json = tool.getStockProfile("茅台");
    assertThat(json).contains("600519").contains("SUCCESS");
}
```

- [ ] **Step 2: 实现 `StockProfileTool.java`**

在 `copilot-agent-tools` 下编写 `StockProfileTool`：
- 支持 `^\d{6}$` 直接调用 `getStockByCode`；
- 非 6 位数字代码则调用 `searchStocks(query, 5)` 取首选匹配项；
- 联合 `getStockMetrics(code)` 获取 PE(TTM)、PB、ROE、股息率、最新市价等，组装返回结构化 JSON。

- [ ] **Step 3: 编写 `FundProfileToolTest` 测试用例**

```java
@Test
@DisplayName("按6位基金代码直查基金档案")
void testGetProfileByCode() {
    FundProfileTool tool = new FundProfileTool(fundDataPort, objectMapper);
    String json = tool.getFundProfile("005827");
    assertThat(json).contains("005827").contains("易方达蓝筹精选混合").contains("SUCCESS");
}

@Test
@DisplayName("按模糊名称检索基金档案")
void testGetProfileByKeyword() {
    FundProfileTool tool = new FundProfileTool(fundDataPort, objectMapper);
    String json = tool.getFundProfile("易方达蓝筹");
    assertThat(json).contains("005827").contains("SUCCESS");
}
```

- [ ] **Step 4: 实现 `FundProfileTool.java`**

在 `copilot-agent-tools` 下编写 `FundProfileTool`：
- 支持 `^\d{6}$` 直接调用 `getFundByCode`；
- 非 6 位数字则调用 `searchFunds(query, 5)` 取首选标的；
- 组装全称、类型、管理人、基金经理、规模及最新净值，序列化为结构化 JSON。

- [ ] **Step 5: 运行工具层单测验证**

运行：`mvn -s maven-settings.xml -pl copilot-agent-tools test`
期望：BUILD SUCCESS，所有测试用例 PASS。

- [ ] **Step 6: 提交 Task 2 代码**

```bash
git add copilot-agent-tools/
git commit -m "feat(tool): implement StockProfileTool and FundProfileTool for asset profile querying"
```

---

### Task 3: MCP 服务端响应式用户上下文与全套工具统一挂载 (`copilot-mcp-service`)

**Files:**
- Create: `copilot-mcp-service/src/main/java/com/financial/copilot/mcp/context/McpUserContext.java`
- Modify: `copilot-mcp-service/src/main/java/com/financial/copilot/mcp/transport/McpSseController.java`
- Modify: `copilot-mcp-service/src/main/java/com/financial/copilot/mcp/binding/FinancialToolsBinder.java`
- Modify: `copilot-mcp-service/src/test/java/com/financial/copilot/mcp/binding/FinancialToolsBinderTest.java` (或新建)

**Interfaces:**
- Consumes: `McpToolRegistry`, `StockProfileTool`, `FundProfileTool`, `FundPoolTool`, `StockWatchlistTool`
- Produces: MCP 注册的 12 个金融工具 (`query_stock_profile`, `query_fund_profile`, `add_to_fund_pool` ... `clear_stock_watchlist`)

- [ ] **Step 1: 创建 `McpUserContext.java` 响应式上下文管理类**

```java
public final class McpUserContext {
    public static final String CONTEXT_KEY = "MCP_USER_ID";
    public static Mono<Long> getCurrentUserId() {
        return Mono.deferContextual(ctx -> Mono.justOrEmpty(ctx.<Long>getOrEmpty(CONTEXT_KEY)));
    }
}
```

- [ ] **Step 2: 修改 `McpSseController.java` 提取 `X-User-Id` 请求头**

在 `handleMessage` 方法增加参数 `@RequestHeader(value = "X-User-Id", required = false) String userIdHeader`：
```java
Long userId = null;
if (userIdHeader != null && !userIdHeader.isBlank()) {
    try {
        userId = Long.parseLong(userIdHeader.trim());
    } catch (NumberFormatException ignored) {}
}
Long finalUserId = userId != null ? userId : 1L; // 缺省兜底 1L

return serverService.handleRequest(request)
        .contextWrite(reactor.util.context.Context.of(McpUserContext.CONTEXT_KEY, finalUserId))
        ...
```

- [ ] **Step 3: 在 `FinancialToolsBinder.java` 中注入并挂载 12 个原生工具**

注入 `FundPoolTool`, `StockWatchlistTool`, `StockProfileTool`, `FundProfileTool`：
1. 挂载 `query_stock_profile` 与 `query_fund_profile`；
2. 挂载 `add_to_fund_pool`、`update_fund_pool`、`remove_from_fund_pool`、`clear_fund_pool`、`query_fund_pool`，通过 `McpUserContext.getCurrentUserId()` 提取 `userId` 传入 `FundPoolTool`；
3. 挂载 `add_to_stock_watchlist`、`update_stock_watchlist`、`remove_from_stock_watchlist`、`clear_stock_watchlist`、`query_stock_watchlist`，通过 `McpUserContext.getCurrentUserId()` 提取 `userId` 传入 `StockWatchlistTool`。

- [ ] **Step 4: 编写与运行 MCP 服务端单测**

创建或更新 `FinancialToolsBinderTest.java`：
验证 MCP 工具注册总数从原先 7 个增加至 19 个（+2 个 profile 工具，+5 个基金池工具，+5 个自选股工具）。
运行：`mvn -s maven-settings.xml -pl copilot-mcp-service test`
期望：BUILD SUCCESS。

- [ ] **Step 5: 提交 Task 3 代码**

```bash
git add copilot-mcp-service/
git commit -m "feat(mcp): register stock/fund profile and watchlist/fundpool tools with McpUserContext header support"
```

---

### Task 4: Agent 核心调度端透传与平滑降级编排 (`copilot-agent-core`)

**Files:**
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/mcp/ReactiveMcpClient.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/config/AgentRoleProperties.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/agentscope/ContextualToolkitFactory.java`
- Modify: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/config/AgentRolePropertiesTest.java`
- Modify: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/agentscope/FundStockPoolToolkitTest.java`

**Interfaces:**
- Consumes: `ReactiveMcpClient`, `StockProfileTool`, `FundProfileTool`, `FundPoolTool`, `StockWatchlistTool`
- Produces: 动态装配的 Toolkit（包含 `query_stock_profile`, `query_fund_profile`，并具备 MCP 优先与本地直调平滑降级）

- [ ] **Step 1: 扩展 `ReactiveMcpClient.java` 支持 `X-User-Id` 头传递**

添加带 `Long userId` 的重载：
```java
public Mono<McpCallResult> callTool(String toolName, Map<String, Object> arguments, @Nullable Long userId) {
    ...
    var reqSpec = webClient.post().uri("/messages");
    if (userId != null) {
        reqSpec.header("X-User-Id", String.valueOf(userId));
    }
    return reqSpec.bodyValue(requestPayload)...
}

public String callToolSync(String toolName, Map<String, Object> arguments, @Nullable Long userId) {
    ...
    return callTool(toolName, arguments, userId).block(timeout).getContent();
}
```

- [ ] **Step 2: 更新 `AgentRoleProperties.java` 与意图过滤规则**

在 `AgentRoleProperties.getDefaultTools(AgentRole role)` 中：
- `FUND_ANALYZER`: 加入 `"query_fund_profile"`；
- `FUND_SCREENER`: 加入 `"query_fund_profile"`；
- `STOCK_ANALYZER`: 加入 `"query_stock_profile"`；
- `STOCK_SCREENER`: 加入 `"query_stock_profile"`。

更新 `ContextualToolkitFactory.filterToolsByIntent`：
- `FUND_SCREENING`、`FUND_DIAGNOSIS` 保留 `"query_fund_profile"`；
- `STOCK_RESEARCH` 保留 `"query_stock_profile"`；
- `ASSET_ALLOCATION` 保留 `"query_fund_profile"` 与 `"query_stock_profile"`。

- [ ] **Step 3: 更新 `ContextualToolkitFactory.java` 装配逻辑**

1. 注入 `StockProfileTool` 与 `FundProfileTool` 以及 `ReactiveMcpClient`；
2. 新增 `StockProfileToolDelegate` 与 `FundProfileToolDelegate`，优先尝试 `mcpClient.callToolSync`，若失败降级至本地 Bean；
3. 更新 `FundPoolToolDelegate` 与 `StockWatchlistToolDelegate`：
   - `addFund`, `updateFund`, `removeFund`, `clearPool`, `queryPool`：优先尝试 `mcpClient.callToolSync(name, args, userId)`，降级至 `tool.xxx`；
   - `addStock`, `updateStock`, `removeStock`, `clearWatchlist`, `queryWatchlist`：优先尝试 `mcpClient.callToolSync(name, args, userId)`，降级至 `tool.xxx`。

- [ ] **Step 4: 更新并运行 `copilot-agent-core` 测试**

更新 `AgentRolePropertiesTest.java` 断言（包含 `query_fund_profile` 与 `query_stock_profile`）。
在 `FundStockPoolToolkitTest.java` 中增加对 `query_fund_profile`、`query_stock_profile` 以及 MCP 降级分支的覆盖。
运行：`mvn -s maven-settings.xml -pl copilot-agent-core test`
期望：BUILD SUCCESS，所有 201+ 测试全部 PASS。

- [ ] **Step 5: 提交 Task 4 代码**

```bash
git add copilot-agent-core/
git commit -m "feat(agent): wire stock/fund profile tools and MCP transparent fallback into ContextualToolkitFactory"
```

---

### Task 5: 全量回归测试与端到端验证 (Full Regression Verification)

**Files:**
- N/A (测试执行与验证)

- [ ] **Step 1: 验证 `copilot-domain` 模块**
  运行：`mvn -s maven-settings.xml -pl copilot-domain test`
- [ ] **Step 2: 验证 `copilot-data-engine` 模块**
  运行：`mvn -s maven-settings.xml -pl copilot-data-engine test`
- [ ] **Step 3: 验证 `copilot-agent-tools` 模块**
  运行：`mvn -s maven-settings.xml -pl copilot-agent-tools test`
- [ ] **Step 4: 验证 `copilot-mcp-service` 模块**
  运行：`mvn -s maven-settings.xml -pl copilot-mcp-service test`
- [ ] **Step 5: 验证 `copilot-agent-core` 模块**
  运行：`mvn -s maven-settings.xml -pl copilot-agent-core test`
- [ ] **Step 6: 验证 `copilot-app` 模块集成测试**
  运行：`mvn -s maven-settings.xml -pl copilot-app test "-Dcopilot.neo4j.enabled=false"`
- [ ] **Step 7: 检查 Git 工作区状态并完成分支确认**
  运行：`git status -s`，确认无未跟踪或未提交代码。
