# FinancialCopilot Supervisor / Subagents 运行时架构与指南

## 1. 架构总览与演进背景

FinancialCopilot 核心投研智能体系统已完成向 **Spring AI Alibaba 官方 Supervisor / Subagents** 多智能体架构的全面重构。
系统彻底淘汰了上一代自研静态/动态 DAG 调度器（`GraphPlannerAgent`、`AgentNodeRouter`、`DagRuntime`、`ExecutionGraph`）以及 AgentScope 依赖，转为由中心总控 Supervisor 与各垂直领域专家子智能体协同的标准协作模式。

### 核心设计原则
1. **中心指挥，专业分工**：`ResearchSupervisor` 担任首席研究总监，根据投资意图与研报要求，按需调用各领域专家 Subagents。
2. **官方标准，标准接入**：专业子智能体全部基于 Spring AI Alibaba `ReactAgent` 构建，并通过官方 `AgentTool.getFunctionToolCallback(...)` 包装，由 `ContextualAgentToolCallback` 透明注入执行凭据与上下文快照。
3. **确定性金融计算隔离**：大模型严禁算术心算与指标捏造，所有收益率、回撤、夏普比率等量化指标必须由 `copilot-math-core` 及底层适配器确定性计算。
4. **单写串行与容量治理**：会话级单写串行化（单会话并发度为 1），防止重入导致上下文错乱与状态竞争；系统级、用户级与 LLM 层均设受控并发容量上限。
5. **本地优先与模型治理**：遵循统一模型驱动规约，默认优先接入本地 Codex-Bridge（端口 `9090`），经由 `GovernedChatModel` 实施并发控制与超时容错。

---

## 2. 系统拓扑与角色分工

```
                        ┌─────────────────────────────────────┐
                        │      HTTP / SSE 入口控制器          │
                        │     (ResearchRunController)         │
                        └──────────────────┬──────────────────┘
                                           │
                                           ▼
                        ┌─────────────────────────────────────┐
                        │     FinancialResearchWorkflow       │
                        │    (唯一调度入口 / 状态机驱动)      │
                        └───────┬─────────────────────┬───────┘
                                │                     │
            ┌───────────────────┴──┐               ┌──┴──────────────────┐
            │ ConversationAdmission│               │ SnapshotFactory     │
            │ (单会话串行与并发准入)│               │ (上下文与用户画像快照)│
            └──────────────────────┘               └─────────────────────┘
                                           │
                                           ▼
                        ┌─────────────────────────────────────┐
                        │         ResearchSupervisor          │
                        │    (Spring AI Alibaba ReactAgent)   │
                        └──────────────────┬──────────────────┘
                                           │ 官方 AgentTool 回调
        ┌───────────────────┬──────────────┼──────────────┬───────────────────┐
        ▼                   ▼              ▼              ▼                   ▼
┌──────────────┐    ┌──────────────┐ ┌───────────┐ ┌──────────────┐    ┌──────────────┐
│ FundAgent    │    │ StockAgent   │ │MarketAgent│ │RiskReview    │    │ ReportAgent  │
│ (公募基金)   │    │ (A股股票)    │ │(知识与宏观│ │(合规与质检)  │    │ (投研报告)   │
└───────┬──────┘    └───────┬──────┘ └─────┬─────┘ └──────┬───────┘    └──────┬───────┘
        │                   │              │              │                   │
        └───────────────────┴──────────────┼──────────────┴───────────────────┘
                                           ▼
                        ┌─────────────────────────────────────┐
                        │   RunEvidenceStore / Ledger         │
                        │  (事实证据库、幂等账本与证据合流)   │
                        └─────────────────────────────────────┘
```

### 智能体职责定义

| 智能体标识 | 职责定位 | 绑定底层工具集 | 权限与限制 |
| :--- | :--- | :--- | :--- |
| **ResearchSupervisor** | 中心调度总监，意图分析、任务分解、统筹各子智能体并驱动交付 | 5 个领域 Subagent Tool 回调 | 负责流程把控与阶段推进，不直接计算数值 |
| **fund_research_agent** | 公募基金档案查询、量化体检、多维筛选、持仓穿透与对比 | `FundResearchTools`（底层委托 `copilot-agent-tools` 与 `copilot-math-core`） | 仅读取并计算基金相关数据，产出 `FUND_METRIC` / `HOLDING` 证据 |
| **stock_research_agent** | A 股股票全维档案、基本面财务（ROE、PE、PB）、行情与量化指标 | `StockResearchTools` | 仅读取并计算股票相关数据，产出 `STOCK_METRIC` 证据 |
| **market_research_agent** | 宏观指标释义、行业板块穿透、知识图谱重合持仓对比与语义召回 | `MarketResearchTools` | 负责指标释义与图谱检索，产出 `MARKET_ANALYSIS` 证据 |
| **risk_review_agent** | 审查各子智能体返回的候选事实证据，核实数据冲突与合规适当性 | 只读上下文与证据库 | 不修改事实数值，产出 `RISK_REVIEW` 证据 |
| **report_agent** | 将已通过风控审查的结构化事实证据合成专业金融投研 Markdown 报告 | 只读上下文与已合流证据库 | 严禁捏造未被验证的数据，产出最终交付成果 |

---

## 3. 核心运行机制

### 3.1 凭据隔离与请求入口
客户端发起投研请求，通过 `FinancialResearchWorkflow.run(ResearchRunRequest request)` 进入系统：
- **`ExecutionIdentity`**：包含 `userId`、`conversationId`、`tenantId`，确保多租户与用户数据严格隔离。
- **`ResearchRunRequest`**：包含会话 ID、用户意图描述、用户问题、偏好资产类型与可选模型路由场景。
- **返回结果**：返回响应式 SSE 事件流 `Flux<ServerSentEvent<ResearchStreamEvent>>`，实时推送状态变更、各智能体思考过程与最终报告片段。

### 3.2 单会话串行与并发准入 (`ConversationAdmissionController`)
- **防止上下文错乱**：每个会话同一时刻只允许一个处于活动状态的 Run 执行（`max-active-runs-per-conversation = 1`）。
- **用户级限流**：单用户跨会话最大并发 Run 数量受控（默认 5）。
- **全局并发与 LLM 槽位**：系统全局并发度与大模型最大调用并发分别受控，超出限额时按策略排队或安全熔断。

### 3.3 运行句柄与生命周期管理 (`SupervisorRunRegistry`)
- 每个执行过程生成唯一的 `ResearchRunHandle`，并在注册表中托管。
- 支持前端通过 `GET /api/research/runs/{runId}/events` 接入 SSE 流，或执行中途取消/中断操作。

### 3.4 证据库与幂等账本 (`RunEvidenceStore` / `AgentOperationLedger`)
- 每次子智能体执行完成，产出的 `AgentEvidence` 通过 `EvidenceMergeCoordinator` 进行版本校验与合流；
- 记录每次操作的 `OperationId`，防止网络波动或模型重试造成重复计算。

---

## 4. 本地 Codex-Bridge 模型服务规范

根据项目开发标准，系统**优先且默认使用本地独立运行的 `codex-bridge`**，严禁硬编码外部官方 API：

### 4.1 服务标准参数
- **Base URL**：`http://127.0.0.1:9090/v1`
- **监听端口**：`9090`
- **API Key**：`local-agy`
- **默认模型**：`gemini-3.8-flash-low`（可按需选用 `gemini-3.8-flash-high`）

### 4.2 本地 Codex-Bridge 启动方式
```powershell
cd D:\BaiduSyncdisk\IdeaProjects\FinancialCopilot\codex-bridge
python -m uvicorn app.main:app --host 127.0.0.1 --port 9090
```

### 4.3 配置文件映射 (`application.yml`)
```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY:local-agy}
      base-url: ${OPENAI_BASE_URL:http://127.0.0.1:9090}
      chat:
        options:
          model: ${MODEL_NAME:gemini-3.8-flash-low}
      audio:
        speech:
          enabled: false
        transcription:
          enabled: false
      image:
        enabled: false

copilot:
  llm:
    default-provider: OPENAI
    default-model: ${MODEL_NAME:gemini-3.8-flash-low}
    default-performance-level: MIDDLE
    api-key: ${OPENAI_API_KEY:local-agy}
    base-url: ${OPENAI_BASE_URL:http://127.0.0.1:9090/v1}
  supervisor:
    capacity:
      max-active-runs-per-conversation: 1
      max-active-runs-per-user: 5
      max-active-runs-global: 20
      acquire-timeout-ms: 0
      max-queued-runs: 20
      max-llm-concurrency: 16
```

---

## 5. 验证与回归测试

执行核心功能单元与模块集成测试（排除本地未启动的 Neo4j / Redis 外部依赖）：

```powershell
# 验证 agent-core 模块
mvn -s maven-settings.xml -gs maven-settings.xml test -pl copilot-agent-core

# 验证 app 层与上下文装配
mvn -s maven-settings.xml -gs maven-settings.xml test -pl copilot-app -am "-Dcopilot.neo4j.enabled=false"

# 全仓库完整验证
mvn -s maven-settings.xml -gs maven-settings.xml test "-Dcopilot.neo4j.enabled=false"
```
