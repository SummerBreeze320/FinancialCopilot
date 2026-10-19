# 基金池与自选股业务功能及 Agent 增删改工具实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `fund` 与 `stock` 领域新增纯用户私有的基金池与自选股功能，实现面向 Agent 的增、删、改、查 Tool，并通过同源约束 `@ToolConstraint` 与高危操作护栏 `@HighRiskTool` 保障安全性。

**Architecture:** 
1. `copilot-domain`：定义纯 Java 领域实体 `FundPoolItem`、`StockWatchlistItem` 及 SPI 端口契约 `FundPoolPort`、`StockWatchlistPort`；
2. `copilot-data-engine`：MyBatis-Plus PO/Mapper 及适配器 `DatabaseFundPoolAdapter`、`DatabaseStockWatchlistAdapter`；
3. `copilot-agent-tools`：实现 `FundPoolTool` 与 `StockWatchlistTool`，入参带 `@ToolConstraint` 校验，清空操作带 `@HighRiskTool` 标记；
4. `copilot-agent-core`：在 `ContextualToolkitFactory` 中装配新工具，安全上下文透明绑定 `userId`，并在意图剪枝中联动。

**Tech Stack:** Java 21, Spring Boot 3.3.3, MyBatis-Plus 3.5.7, AgentScope 2.0 Core, Lombok, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-20-fund-pool-and-stock-watchlist-design.md`

## Global Constraints

- 严格遵循用户私有隔离原则，所有增删改查均以当前上下文认证 `userId` 为主键前缀；
- 严禁要求 Agent 在工具调用中显式传入 `userId`，避免大模型幻觉与越权；
- 清空操作（`clear_fund_pool`、`clear_stock_watchlist`）必须标注 `@HighRiskTool`；
- 所有代码遵守 Chinese Javadoc、Lombok、不可变 DTO 规范；
- 模块测试保持 100% 通过率。

---

### Task 1: 领域层实体与 SPI 端口定义 (`copilot-domain`)

**Files:**
- Create: `copilot-domain/src/main/java/com/financial/copilot/domain/business/fund/entity/FundPoolItem.java`
- Create: `copilot-domain/src/main/java/com/financial/copilot/domain/business/fund/port/FundPoolPort.java`
- Create: `copilot-domain/src/main/java/com/financial/copilot/domain/business/stock/entity/StockWatchlistItem.java`
- Create: `copilot-domain/src/main/java/com/financial/copilot/domain/business/stock/port/StockWatchlistPort.java`
- Test: `copilot-domain/src/test/java/com/financial/copilot/domain/business/fund/FundPoolEntityTest.java`

**Interfaces:**
- Produces: `FundPoolItem`, `FundPoolPort`, `StockWatchlistItem`, `StockWatchlistPort`

- [ ] **Step 1: 编写实体测试 `FundPoolEntityTest.java`**

- [ ] **Step 2: 运行测试验证失败**

运行: `mvn -s maven-settings.xml -pl copilot-domain test "-Dtest=FundPoolEntityTest"`
预期: 找不到类编译错误

- [ ] **Step 3: 编写领域实体与 SPI 端口契约**

编写 `FundPoolItem.java`、`StockWatchlistItem.java`、`FundPoolPort.java`、`StockWatchlistPort.java`。

- [ ] **Step 4: 运行测试验证通过**

运行: `mvn -s maven-settings.xml -pl copilot-domain test "-Dtest=FundPoolEntityTest"`
预期: PASS

- [ ] **Step 5: 提交 Task 1**

```powershell
git add copilot-domain/ ; git commit -m "feat(domain): define FundPoolItem, StockWatchlistItem and their SPI ports"
```

---

### Task 2: 数据持久层 PO、Mapper 与适配器 (`copilot-data-engine`)

**Files:**
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/fund/po/FundPoolPO.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/fund/mapper/FundPoolMapper.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/fund/adapter/DatabaseFundPoolAdapter.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/stock/po/StockWatchlistPO.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/stock/mapper/StockWatchlistMapper.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/stock/adapter/DatabaseStockWatchlistAdapter.java`
- Test: `copilot-data-engine/src/test/java/com/financial/copilot/data/fund/DatabaseFundPoolAdapterTest.java`
- Test: `copilot-data-engine/src/test/java/com/financial/copilot/data/stock/DatabaseStockWatchlistAdapterTest.java`

**Interfaces:**
- Consumes: `FundPoolPort`, `StockWatchlistPort`
- Produces: Spring Components `DatabaseFundPoolAdapter`, `DatabaseStockWatchlistAdapter`

- [ ] **Step 1: 编写适配器单元测试**

使用 Mockito mock MyBatis-Plus Mapper，测试增删改查逻辑。

- [ ] **Step 2: 运行测试验证失败**

运行: `mvn -s maven-settings.xml -pl copilot-data-engine test "-Dtest=DatabaseFundPoolAdapterTest,DatabaseStockWatchlistAdapterTest"`

- [ ] **Step 3: 实现 PO、Mapper 与 Adapter**

实现 `FundPoolPO`、`FundPoolMapper`、`DatabaseFundPoolAdapter` 及 `StockWatchlist` 相关组件。

- [ ] **Step 4: 运行测试验证通过**

运行: `mvn -s maven-settings.xml -pl copilot-data-engine test "-Dtest=DatabaseFundPoolAdapterTest,DatabaseStockWatchlistAdapterTest"`
预期: PASS

- [ ] **Step 5: 提交 Task 2**

```powershell
git add copilot-data-engine/ ; git commit -m "feat(data): implement MyBatis-Plus PO, Mapper and Adapters for fund pool and stock watchlist"
```

---

### Task 3: 面向 Agent 的工具层实现与护栏注解 (`copilot-agent-tools`)

**Files:**
- Create: `copilot-agent-tools/src/main/java/com/financial/copilot/agent/tools/fund/FundPoolTool.java`
- Create: `copilot-agent-tools/src/main/java/com/financial/copilot/agent/tools/stock/StockWatchlistTool.java`
- Test: `copilot-agent-tools/src/test/java/com/financial/copilot/agent/tools/fund/FundPoolToolTest.java`
- Test: `copilot-agent-tools/src/test/java/com/financial/copilot/agent/tools/stock/StockWatchlistToolTest.java`

**Interfaces:**
- Consumes: `FundPoolPort`, `StockWatchlistPort`
- Produces: Spring Components `FundPoolTool`, `StockWatchlistTool`

- [ ] **Step 1: 编写工具层单元测试**

验证工具的增、删、改、查、清空方法执行逻辑与返回 JSON 结构。

- [ ] **Step 2: 运行测试验证失败**

运行: `mvn -s maven-settings.xml -pl copilot-agent-tools test "-Dtest=FundPoolToolTest,StockWatchlistToolTest"`

- [ ] **Step 3: 实现 `FundPoolTool` 与 `StockWatchlistTool`**

使用 `@Tool`、`@ToolParam`、`@ToolConstraint`（代码6位正则，目标仓位 0~1 区间）以及 `@HighRiskTool`（清空操作）进行注解修饰。

- [ ] **Step 4: 运行测试验证通过**

运行: `mvn -s maven-settings.xml -pl copilot-agent-tools test "-Dtest=FundPoolToolTest,StockWatchlistToolTest"`
预期: PASS

- [ ] **Step 5: 提交 Task 3**

```powershell
git add copilot-agent-tools/ ; git commit -m "feat(tool): implement FundPoolTool and StockWatchlistTool with ToolConstraint and HighRisk annotations"
```

---

### Task 4: Toolkit 装配、安全上下文注入与意图联动 (`copilot-agent-core`)

**Files:**
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/config/AgentRoleProperties.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/agentscope/ContextualToolkitFactory.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/agentscope/FundStockPoolToolkitTest.java`

**Interfaces:**
- Produces: 动态注入 `fund_pool` 和 `stock_watchlist` 单原子工具

- [ ] **Step 1: 编写 Toolkit 注册集成测试**

验证在指定角色和意图下，Toolkit 正确挂载 `fund_pool` 和 `stock_watchlist` 工具。

- [ ] **Step 2: 运行测试验证失败**

运行: `mvn -s maven-settings.xml -pl copilot-agent-core test "-Dtest=FundStockPoolToolkitTest"`

- [ ] **Step 3: 更新 `AgentRoleProperties` 与 `ContextualToolkitFactory`**

注入 `FundPoolTool` 与 `StockWatchlistTool`，并为 `FUND_ANALYZER`、`STOCK_ANALYZER`、`ASSET_ALLOCATOR` 等角色开通基准工具权限与意图路由。

- [ ] **Step 4: 运行测试验证通过**

运行: `mvn -s maven-settings.xml -pl copilot-agent-core test "-Dtest=FundStockPoolToolkitTest"`
预期: PASS

- [ ] **Step 5: 提交 Task 4**

```powershell
git add copilot-agent-core/ ; git commit -m "feat(agent): register fund_pool and stock_watchlist tools into ContextualToolkitFactory"
```

---

### Task 5: 全量回归测试与端到端验证

- [ ] **Step 1: 执行本地依赖安装**

运行: `mvn -s maven-settings.xml clean install -DskipTests`

- [ ] **Step 2: 执行全量测试**

运行: `mvn -s maven-settings.xml -pl copilot-agent-tools test`
运行: `mvn -s maven-settings.xml -pl copilot-agent-core test`
运行: `mvn -s maven-settings.xml -pl copilot-app test`

- [ ] **Step 3: 检查工作树**

运行: `git status` 确保干净无残留。
