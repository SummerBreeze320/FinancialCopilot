# 金融多资产研究 Agent（公募基金深度实施版）落地实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建具备通用多资产可扩展架构（首期深度落地公募基金领域，具备股票、期货、银行理财扩展性），基于 AgentScope Java 2.x、Spring Boot 3.3.3、Java 21、Lombok + MyBatis-Plus 与 PostgreSQL 16 + PGVector，能够自主解构并执行复杂复合投研流水线（如“筛选医药基金 -> 评估前5名经理 -> 对比最优2个 -> 输出投资配置建议”）的金融研究 Agent。

**Architecture:** 
1. **多资产分层底座**：抽象出 `AssetCategory`, `AssetProfile`, `AssetDomainStrategy`, `AssetDomainRegistry`，明确划分通用投研层与特定资产插件层。公募基金（Fund）为首发深度实现，包命名与命名空间清晰隔离（`fund`）。
2. **复合流水线解构器**：引入 `TaskDecomposer` 识别复合拓扑任务，生成 `ExecutionPlan`；通过 `ResearchBlackboard` 跨步骤安全传递量化结果与候选标的；通过 Java 21 虚拟线程支持批量经理评估的 Fan-Out / Fan-In 并发提速。
3. **数据访问与模型标准**：全面采用 **Lombok** 消除冗余模板代码，持久层采用 **MyBatis-Plus 3.5.x for Spring Boot 3**，单表 CRUD 零 XML，PGVector 向量检索通过注解式 SQL 极简实现。
4. **Tool-as-Truth 规范**：夏普、回撤、卡玛、胜率等指标由 `copilot-math-core` 确定性高精度计算，严防模型幻觉；定期报告定性分析通过向量语义检索赋能。
5. **阶段式 SSE 流式交互**：实时输出执行阶段事件（`step_start`, `step_complete`）与研报打字机流。

**Tech Stack:** Java 21 LTS (Virtual Threads), Spring Boot 3.3.3, AgentScope Java 2.0.0, Lombok 1.18.34, MyBatis-Plus 3.5.7+, PostgreSQL 16 + PGVector 0.1.6, Python 3.10+ (AkShare 离线同步), DeepSeek API。

**Spec:** [docs/superpowers/specs/2026-09-10-financial-research-agent-design.md](file:///d:/BaiduSyncdisk/IdeaProjects/FinancialCopilot/docs/superpowers/specs/2026-09-10-financial-research-agent-design.md)

---

## 模块结构与包划分规划

```text
financial-copilot/
├── pom.xml                                           // 根 POM: Java 21, Spring Boot 3.3.3, MyBatis-Plus, Lombok
├── copilot-common/                                   // 公共通用层与多资产基础规范
│   └── src/main/java/com/financial/copilot/common/
│       ├── enums/AssetCategory.java                  // [多资产] 资产大类 (FUND, STOCK, FUTURES, WEALTH)
│       ├── model/AssetProfile.java                   // [多资产] 统一资产简档模型
│       ├── result/ApiResult.java                     // 统一 API 响应包装
│       └── fund/dto/                                 // [基金专属]
│           ├── FundScreeningCriteria.java            // 选基 DSL
│           └── FundMetricsDTO.java                   // 基金量化指标传输对象
├── copilot-domain/                                   // 领域模型与核心业务抽象
│   └── src/main/java/com/financial/copilot/domain/
│       ├── core/strategy/AssetDomainStrategy.java    // [多资产] 领域策略抽象
│       ├── core/registry/AssetDomainRegistry.java    // [多资产] 领域路由中心
│       └── fund/                                     // [基金专属] 领域核心模型
│           ├── entity/ (FundInfo, FundManager, FundCompany, FundNavHistory, FundHolding)
│           └── port/FundDataPort.java                // 数据访问 SPI 接口
├── copilot-math-core/                                // 原生金融量化计算引擎 (高精度纯 Java)
│   ├── src/main/java/com/financial/copilot/math/FinancialMathUtils.java
│   └── src/test/java/com/financial/copilot/math/FinancialMathUtilsTest.java
├── copilot-data-engine/                              // 持久层：Lombok + MyBatis-Plus + PGVector
│   └── src/main/java/com/financial/copilot/data/fund/
│       ├── po/ (FundInfoPO, FundManagerPO, FundNavHistoryPO, FundReportVectorPO)
│       ├── mapper/ (FundInfoMapper, FundNavHistoryMapper, FundReportVectorMapper)
│       └── adapter/DatabaseFundDataAdapter.java      // 实现 FundDataPort
├── copilot-agent-tools/                              // AgentScope 工具包
│   └── src/main/java/com/financial/copilot/agent/tools/fund/
│       ├── FundScreeningTool.java                    // 基金初筛
│       ├── FundQuantAnalysisTool.java                // 基金经理与标的量化体检
│       ├── FundHoldingsQueryTool.java                // 持仓与集中度穿透
│       └── FundReportRetrieverTool.java              // 季报切片向量语义检索
├── copilot-agent-core/                               // 多智能体编排与复合流水线
│   └── src/main/java/com/financial/copilot/agent/core/
│       ├── pipeline/
│       │   ├── TaskDecomposer.java                   // 复合任务解构器
│       │   ├── ResearchBlackboard.java               // 投研黑板上下文
│       │   └── ExecutionPlan.java                    // 执行计划模型
│       ├── agents/ (ScreenerAgent, AnalyzerAgent, ComparatorAgent, ReportSynthesizer)
│       └── workflow/FinancialResearchWorkflow.java   // 复合投研编排器
├── copilot-app/                                      // 启动入口与 Web 控制器
│   └── src/main/java/com/financial/copilot/
│       ├── FinancialCopilotApplication.java
│       └── controller/ResearchAgentController.java   // 阶段式 SSE 流式端点
└── scripts/data_sync/                                // 数据抽取工具集 (Python AkShare)
```

---

## 阶段实施任务清单

### Task 1: 依赖库升级与持久层标准化（Lombok + MyBatis-Plus 引入）
- [ ] 在根目录 `pom.xml` 中引入 `mybatis-plus-spring-boot3-starter` (3.5.7) 管理，移除原有 Spring Data JPA 依赖。
- [ ] 确保子模块 `copilot-common`, `copilot-domain`, `copilot-data-engine` 正确应用 Lombok (`@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`)。
- [ ] 改造 `copilot-data-engine`：将原有 JPA Entity 改造为标准的 MyBatis-Plus PO (`FundInfoPO`, `FundManagerPO`, `FundNavHistoryPO`, `FundReportVectorPO`)，使用 `@TableName` 和 `@TableId`。
- [ ] 编写对应的 Mapper 接口继承 `BaseMapper<PO>`：`FundInfoMapper`, `FundNavHistoryMapper`, `FundReportVectorMapper`。
- [ ] 在 `FundReportVectorMapper` 中使用 MyBatis 注解 `@Select` 实现 PGVector `<=>` 余弦相似度召回。
- [ ] 重构 `DatabaseFundDataAdapter`，通过 MyBatis-Plus `LambdaQueryWrapper` 与 Mapper 实现 `FundDataPort`。
- [ ] 运行 `mvn test-compile` 验证持久层编译与测试通过。

### Task 2: 多资产架构抽象与基金领域命名空间隔离
- [ ] 在 `copilot-common` 中创建通用多资产包：
  - 定义 `com.financial.copilot.common.enums.AssetCategory` (`FUND`, `STOCK`, `FUTURES`, `WEALTH_MANAGEMENT`)。
  - 定义 `com.financial.copilot.common.model.AssetProfile`。
- [ ] 在 `copilot-domain` 中创建多资产策略注册抽象：
  - 定义 `com.financial.copilot.domain.core.strategy.AssetDomainStrategy` 接口。
  - 定义 `com.financial.copilot.domain.core.registry.AssetDomainRegistry` 注册中心。
- [ ] 将所有现有基金相关代码整齐划归至 `fund` 专属包路径：
  - `copilot-domain/src/.../domain/fund/`
  - `copilot-agent-tools/src/.../tools/fund/`
- [ ] 编译并验证多资产底座结构清晰、基金特征鲜明。

### Task 3: 复合投研任务解构器与投研黑板（copilot-agent-core）
- [ ] 在 `copilot-agent-core` 的 `pipeline` 包下定义数据结构：
  - `ExecutionPlan`：包含 `isComplex`, `summary`, `List<SubTask> steps`。
  - `SubTask`：包含 `stepIndex`, `taskType` (`SCREENING`, `BATCH_ANALYSIS`, `COMPARISON`, `SYNTHESIS`), `description`, `dependsOn`, `params`, `outputKey`。
  - `ResearchBlackboard`：基于并发安全 Map 的状态上下文，支持存放 `candidateFunds`, `managerRatings`, `topCandidates`, `comparisonFacts`, `finalReport`。
- [ ] 编写 `TaskDecomposer`：
  - 针对用户自然语言输入（如“帮我筛选过去三年表现稳定的医药基金，然后分析前 5 名基金经理的能力，再比较其中最优秀的两个，最后生成投资建议”），通过 DeepSeek 智能识别是否为复合任务。
  - 复合任务生成有序的 4 步 Plan，单意图任务退化为 1 步 Plan。
- [ ] 编写针对 `TaskDecomposer` 的单元测试，验证单意图与复合意图解析正确性。

### Task 4: 复合流水线编排与并发评估引擎（Fan-Out / Fan-In）
- [ ] 在 `copilot-agent-core` 中重构 `FinancialResearchWorkflow`：
  - 接入 `TaskDecomposer` 获得 `ExecutionPlan`。
  - 按照步骤顺序推进，前一步输出注入 `ResearchBlackboard`，作为后一步输入。
  - 对于阶段 2（批量经理评估）：基于 Java 21 虚拟线程实现 Fan-Out 并发拉取并计算量化指标，排序后 Fan-In 筛选最优标的。
  - 对于阶段 3（横向对标）：自动读取 Blackboard 中的 Top 2 标的，调用定量对比和季报 RAG 语义检索，形成事实对照表。
  - 对于阶段 4（报告合成）：由 `ReportSynthesizer` 汇集 Blackboard 中所有事实，产出结构化投资建议报告。
- [ ] 编写复合流水线端到端单测，验证黑板上下文完整流转。

### Task 5: 阶段式 SSE 消息协议与流式输出（copilot-app）
- [ ] 在 `copilot-common` 定义流式事件消息体 `ResearchStreamEvent`（支持 `PLAN`, `STEP_START`, `STEP_COMPLETE`, `CONTENT`, `DONE`）。
- [ ] 升级 `ResearchAgentController.java` 中的 `/api/v1/research/chat/stream` 端点：
  - 以 `MediaType.TEXT_EVENT_STREAM_VALUE` 实时推送执行进度。
  - 用户界面可直观感知投研思考链路（“步骤 1/4 初筛中... -> 步骤 2/4 经理评估中... -> 步骤 3/4 季报对标中... -> 步骤 4/4 报告打字机流式呈现”）。
- [ ] 模拟真实复合输入场景进行完整验证并输出演示。
