# 基金池与自选股业务功能及 Agent 增删改工具设计规范

## 1. 业务背景与建设目标

### 1.1 现状分析
在现有的 `FinancialCopilot` 系统中，面向大模型 Agent 开放的工具库集中于只读型探测与定量计算（如基金/股票指标计算、持仓前瞻、公告研报检索、DSL 条件初筛等）。
Agent 在完成标的深度体检、业绩归因与对比初筛后，缺乏将高价值分析成果直接落盘、持久化并动态维护的能力。

### 1.2 建设目标
在 `fund`（基金）与 `stock`（股票）两大领域下分别扩展资产池业务能力：
1. **基金池 (Fund Pool)**：支持按用户私有隔离，维护核心池 (CORE)、观察池 (WATCH)、备选池 (CANDIDATE)，记录标的代码、名称、目标配置仓位 (`targetWeight`)、入池理由与标签；
2. **自选股 (Stock Watchlist)**：支持用户自定义分组（如默认、半导体芯片、高股息红利），记录标的代码、名称、目标建仓/止盈止损价位 (`targetPrice`)、关注理由与优先级；
3. **面向 Agent 开放刚性受控的增、删、改、查 Tool**：
   - 标注 `@ToolConstraint` 进行代码正则与数值范围校验；
   - 对批量清空等破坏性操作标注 `@HighRiskTool` 触发动作护栏 `ASK` 人机协同审批；
   - 用户身份 (`userId`) 统一由底层安全上下文透明绑定，彻底杜绝大模型越权或参数伪造。

---

## 2. 领域层设计 (`copilot-domain`)

### 2.1 实体模型
- **`FundPoolItem`**
  - `id`: Long
  - `userId`: Long (强隔离)
  - `fundCode`: String (6位基金代码)
  - `fundName`: String
  - `poolType`: String (`CORE`, `WATCH`, `CANDIDATE`)
  - `targetWeight`: BigDecimal (0.0000 ~ 1.0000)
  - `reason`: String
  - `tags`: String
  - `createdAt`: Instant
  - `updatedAt`: Instant

- **`StockWatchlistItem`**
  - `id`: Long
  - `userId`: Long (强隔离)
  - `stockCode`: String (6位股票代码)
  - `stockName`: String
  - `groupName`: String (自选分组，默认 "默认")
  - `targetPrice`: BigDecimal
  - `notes`: String
  - `priority`: String (`HIGH`, `MEDIUM`, `LOW`)
  - `createdAt`: Instant
  - `updatedAt`: Instant

### 2.2 领域 SPI 端口契约
- **`FundPoolPort`**
  ```java
  public interface FundPoolPort {
      FundPoolItem addOrUpdate(FundPoolItem item);
      boolean remove(Long userId, String fundCode, String poolType);
      int clear(Long userId, String poolType);
      Optional<FundPoolItem> findByUserAndCode(Long userId, String fundCode, String poolType);
      List<FundPoolItem> listByUser(Long userId, String poolType);
  }
  ```
- **`StockWatchlistPort`**
  ```java
  public interface StockWatchlistPort {
      StockWatchlistItem addOrUpdate(StockWatchlistItem item);
      boolean remove(Long userId, String stockCode, String groupName);
      int clear(Long userId, String groupName);
      Optional<StockWatchlistItem> findByUserAndCode(Long userId, String stockCode, String groupName);
      List<StockWatchlistItem> listByUser(Long userId, String groupName);
  }
  ```

---

## 3. 数据持久层设计 (`copilot-data-engine`)

### 3.1 关系表物理结构
1. **`user_fund_pool`**
   - 唯一键约束：`UNIQUE (user_id, fund_code, pool_type)`
   - 索引：`idx_ufp_user (user_id)`
2. **`user_stock_watchlist`**
   - 唯一键约束：`UNIQUE (user_id, stock_code, group_name)`
   - 索引：`idx_usw_user (user_id)`

### 3.2 持久层映射与适配
- `FundPoolPO` & `FundPoolMapper` (MyBatis-Plus)
- `StockWatchlistPO` & `StockWatchlistMapper` (MyBatis-Plus)
- `DatabaseFundPoolAdapter` 实现 `FundPoolPort`
- `DatabaseStockWatchlistAdapter` 实现 `StockWatchlistPort`
- 提供基于内存并发 Map 的内存适配器便于无 DB 环境单测。

---

## 4. 工具层与安全护栏设计 (`copilot-agent-tools`)

### 4.1 基金池工具 `FundPoolTool`
- `add_to_fund_pool(fundCode, poolType, targetWeight, reason, tags)`: 增/改
- `update_fund_pool(fundCode, poolType, targetWeight, reason)`: 针对目标仓位微调
- `remove_from_fund_pool(fundCode, poolType)`: 移出单只标的
- `clear_fund_pool(poolType)`: 【标注 `@HighRiskTool(category = "ASSET_TRANSACTION", riskLevel = "HIGH", requiresApproval = true)`】清空特定池
- `query_fund_pool(poolType)`: 只读查询持仓

### 4.2 自选股工具 `StockWatchlistTool`
- `add_to_stock_watchlist(stockCode, groupName, targetPrice, notes, priority)`: 增/改
- `update_stock_watchlist(stockCode, groupName, targetPrice, notes)`: 更新价位与笔记
- `remove_from_stock_watchlist(stockCode, groupName)`: 移除单只股票
- `clear_stock_watchlist(groupName)`: 【标注 `@HighRiskTool`】清空指定分组
- `query_stock_watchlist(groupName)`: 只读查询列表

### 4.3 用户上下文与防越权
- 工具在被 Agent 执行时，通过上下文提取当前 Session 的有效 `userId`，不从 Agent 入参读取 `userId`，确保多租户/用户私有自选数据物理级安全。

---

## 5. AgentScope 装配与意图感知集成 (`copilot-agent-core`)

### 5.1 `ContextualToolkitFactory` 注入
- 注册原子工具：
  - `fund_pool` -> 代理 `FundPoolTool`
  - `stock_watchlist` -> 代理 `StockWatchlistTool`
- 角色基础权限映射：
  - `FUND_ANALYZER`, `FUND_SCREENER`, `ASSET_ALLOCATOR` -> 包含 `fund_pool`
  - `STOCK_ANALYZER`, `STOCK_SCREENER`, `ASSET_ALLOCATOR` -> 包含 `stock_watchlist`
- 意图 RAG 动态剪枝：
  - `FUND_SCREENING` / `FUND_DIAGNOSIS` -> 保留 `fund_pool`
  - `STOCK_RESEARCH` -> 保留 `stock_watchlist`
  - `ASSET_ALLOCATION` -> 同时保留 `fund_pool` 和 `stock_watchlist`
