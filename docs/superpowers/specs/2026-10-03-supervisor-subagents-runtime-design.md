# Supervisor-Subagents 投研运行时重构设计

## 1. 目标与范围

将投研执行链路重构为显式的 `Supervisor → Planner → Scheduler → Subagents → Validator → Reviewer → Aggregator` 闭环，并新增独立可启动的 `copilot-mcp-server` 作为外部 MCP Tool Server 的动态注册、发现和代理服务。保留 `FinancialResearchWorkflow.run(GraphRunRequest)` 作为 JSON、SSE、恢复和取消的唯一新 Run 入口；不迁移现有 `copilot-agent-tools` 实现，也不改动 Stock 数据扩展、NER 重命名、RAG、Docker 等无关工作区改动。

本设计不会把所有控制逻辑交给大模型。LLM 只参与任务规划、领域执行、跨证据审查和报告聚合；身份、鉴权、调度、资源限制、计费、持久化及确定性数据校验仍由 Java 服务负责。

## 2. 总体结构

```text
Query
  → SupervisorWorkflow
      → PlannerAgent → Validated TaskDAG
      → Scheduler → Specialist Subagents / deterministic tool nodes
      → Validator → ReviewerAgent
      → AggregatorAgent → FINAL_REPORT
      ↖ retry targeted task / replan remaining DAG / degrade
```

`SupervisorWorkflow` 是 `FinancialResearchWorkflow` 内部的应用编排边界，而非拥有任意业务工具的通用 ReAct Agent。它只能根据 `ReviewDecision` 和运行状态选择继续、重试目标节点、局部重规划、降级输出或结束。

## 3. 组件职责与契约

### 3.1 PlannerAgent

Planner 将 prompt、不可变 `ExecutionIdentity`、会话快照和允许能力目录转换为受 schema 约束的 `TaskDAG`。每个任务必须声明：稳定 `taskId`、角色、输入 Artifact 契约、输出 Artifact 契约、依赖、可重试次数、超时和资源需求。Planner 不得指定 userId、tenantId、runId、计费金额或实际资源许可。

`TaskDAG` 经确定性 `PlanValidator` 校验后才可映射至现有 `ExecutionGraph`；拒绝环、未注册角色、越权工具、缺失输入绑定和超出预设资源边界的计划。

### 3.2 Scheduler

Scheduler 继续使用 `RunAdmissionController` 和 `GlobalReadyScheduler`。它根据依赖、用户/会话配额、全局容量和取消令牌调度节点，不接受模型生成的资源配额作为系统容量依据。相同会话的 Run 串行；同一 Run 内无依赖的节点可并行。

### 3.3 Specialist Subagents

Subagent 按角色注册专属、不可变 Toolkit，例如基金研究、股票研究、财报解析、风险指标、市场记忆和报告资料整理。工具通过 `RuntimeContext` 获取 `ExecutionIdentity` 与 `NodeExecutionContext`；模型不可传入或覆盖这些值。

量化指标必须由既有数据工具和 `copilot-math-core` 计算。Subagent 只能解释工具产物，不得自行推导收益、回撤、Sharpe、Calmar 或其他金融数值。

### 3.4 Validator

Validator 是确定性 Java 组件，在产物进入审查或聚合前执行：Artifact 类型/Schema 校验、必填证据和字段检查、数据日期和标的合法性、用户所有权与 memory scope、数值来源标记、以及节点输出大小限制。失败产生结构化 `ValidationResult`，不会让模型猜测如何修复。

### 3.5 ReviewerAgent

Reviewer 读取经 Validator 通过的 Artifact 摘要及证据合同，审查跨节点一致性、问题覆盖度、冲突、风险披露与是否足以回答用户。它不得直接调用写操作工具，也不得生成最终报告。

输出为：

```java
public record ReviewDecision(
        ReviewAction action,
        List<String> targetTaskIds,
        List<String> issues,
        List<String> requiredEvidence,
        double confidence
) {}

public enum ReviewAction {
    PASS, RETRY_TASK, REPLAN, DEGRADE
}
```

`RETRY_TASK` 仅可指向可重试、未超过尝试次数的任务；`REPLAN` 仅可修改尚未运行的 DAG 部分；`DEGRADE` 保留已验证事实并将缺口写入最终报告。

### 3.6 AggregatorAgent

Aggregator（现有 `ReportSynthesizerAgent` 的目标职责）仅接收 Validator 已通过且 Reviewer 允许使用的 Artifact。它生成 `FINAL_REPORT`，将来源、数据截止日期和 Reviewer 标识的限制显式呈现；不得添加无证据的量化结论。

## 4. 闭环状态机与边界

每个 Run 的 Supervisor 状态为：`PLANNING → EXECUTING → VALIDATING → REVIEWING → AGGREGATING → TERMINAL`。Validator 失败时以节点的 FailurePolicy 处理；Reviewer 决策则驱动以下转换：

| 决策 | 行为 |
| --- | --- |
| PASS | 进入聚合 |
| RETRY_TASK | 仅将目标任务重新入队，然后回到 EXECUTING |
| REPLAN | 调用 Planner 生成 GraphPatch，校验后回到 EXECUTING |
| DEGRADE | 携带缺口与限制进入聚合 |

Supervisor 必须设置 `maxReviewCycles`、每节点 `maxAttempts`、Run 总超时、LLM/点数预算；任一限制耗尽时强制 `DEGRADE` 或按明确 FailurePolicy 失败，严禁无限循环。

## 5. 持久化、事件与安全

PostgreSQL 仍是 Run、节点尝试、租约、计费预留及可重放事件的事实来源；本地 Registry 和 Redis 仅为加速层。状态变化和 SSE 事件必须单次持久化，避免排队外层事件总线与 Runtime 内层事件总线重复追加。

`ExecutionIdentity` 是服务器派生的不可变边界。所有 memory-service 调用（包括写入、读取上下文、清理会话）均使用用户/会话命名空间与内部服务令牌；未配置令牌的生产配置必须拒绝服务请求。用户不可读取、取消、恢复或订阅其他用户的 Run。

## 6. 独立 MCP Tool 注册服务

新增 Maven 模块 `copilot-mcp-server`，作为独立 Spring Boot 进程启动、独立配置端口、独立 Docker 镜像和健康检查。它以 MCP Streamable HTTP Server 面向 `copilot-agent-core` 中的 AgentScope MCP Client；以 MCP Client 面向已经独立部署的外部 Tool Server。它不是现有 Java Tool Bean 的迁移容器，第一期不复制或迁移 `copilot-agent-tools` 的任何工具实现。

### 6.1 动态注册 API

外部 Tool Server 通过受服务令牌保护的注册接口申请上线：

```http
POST /internal/v1/tool-servers/registrations
X-Copilot-Service-Token: <service-token>
Content-Type: application/json

{
  "serverId": "market-data-primary",
  "displayName": "行情数据服务",
  "endpoint": "https://market-tools.internal/mcp",
  "transport": "STREAMABLE_HTTP",
  "capabilities": ["fund.market.read"],
  "leaseSeconds": 60
}
```

注册成功后，Registry 通过该 endpoint 建立 MCP Client 握手、获取真实 tool list 和每个工具的 input schema。请求中的 `toolName`、描述和 schema 不作为可信来源；仅握手得到的结果可被记录为可调用工具。`serverId`、endpoint 和 capability 均使用白名单及 SSRF 防护校验；endpoint 必须是允许的 HTTPS 内网域名或显式允许的开发环境地址。

注册成功响应返回不可猜测的 `registrationId`、状态和续约期限。服务端在 lease 到期前通过以下接口续约；未续约的服务及其工具立即从发现结果移除，正在执行的调用按既有调用超时处理：

```http
PUT /internal/v1/tool-servers/registrations/{registrationId}/lease
X-Copilot-Service-Token: <service-token>
```

注销使用 `DELETE /internal/v1/tool-servers/registrations/{registrationId}`。注册、续约和注销均写入审计事件；不同服务令牌不能操作其他服务的 registration。

### 6.2 工具发现与调用

Registry 仅向 Agent 暴露与其 `AgentRole`、`ExecutionIdentity`、数据 scope 和只读/写入政策匹配的工具。Planner 读取去除 endpoint、服务令牌和内部描述后的 capability catalog；Subagent 从角色 Toolkit 得到经过筛选的 MCP Tool。每次调用由 Registry 代理至所属外部 MCP Server，并附加服务器签发的短期调用凭据或 mTLS 身份；永不向模型暴露服务注册令牌。

Registry 强制每工具超时、并发上限、请求大小上限、响应大小上限、审计 ID 和结构化错误。工具响应必须绑定 `registrationId`、工具版本、调用时间和来源 Server，以供 Validator、Reviewer 和 SSE 事件引用。

### 6.3 注册状态与持久化

注册状态为 `PENDING`、`ACTIVE`、`UNHEALTHY`、`EXPIRED`、`REVOKED`。PostgreSQL 保存注册、版本、租约、健康结果和审计事件；内存或 Redis 仅缓存活跃目录。MCP server 启动后从 PostgreSQL 恢复有效注册并重新握手，无法验证的注册进入 `UNHEALTHY`，不得被 Agent 调用。

只有内部服务身份可访问注册 API；最终用户身份不能注册外部工具。Registry 的 MCP 调用接口仍由 FinancialCopilot 内部服务身份保护，并将最终用户 `ExecutionIdentity` 用作授权与审计边界。

## 7. 验收标准

1. 同步与 SSE 均通过同一 SupervisorWorkflow 和 `FinancialResearchWorkflow.run(...)`。
2. Planner 的非法 DAG 在调度前被拒绝；未注册角色和模型自带身份不能进入执行路径。
3. 无依赖 Specialist 可并行，且调度仍遵守用户、会话、Run 和系统容量上限。
4. Validator 拒绝未满足 Artifact/证据/数值来源契约的产物。
5. Reviewer 的四种决策均被 Supervisor 有界处理，且只重跑目标节点或局部重规划。
6. Aggregator 不消费未审查或被拒绝的产物，并在降级时明确披露限制。
7. 每个 Run 的审查决策、重试和最终聚合事件在 SSE 重连后以单调序列重放一次。
8. PostgreSQL 租约必须先 claim、再 renew/complete；多实例恢复不依赖 JVM 内存队列。
9. 动态注册 API 拒绝无效服务令牌、越权续约、非白名单 endpoint、握手失败和伪造 tool schema；租约到期的工具不能再被发现或调用。
10. Registry 只将角色已授权的外部工具暴露给 Planner/Subagent，且工具调用审计可关联 registration、工具版本、Run 与用户。
11. 单元测试覆盖状态机分支；集成测试覆盖租约、SSE 回放、所有权、钱包结算及 MCP 注册/代理；基础 Maven 测试无需 PostgreSQL、Redis、Neo4j、Ollama。

## 8. 迁移顺序

1. 建立 Supervisor 状态机、ReviewDecision 契约和单元测试。
2. 把现有 GraphPlanner 显式限定为 Planner，并在图校验后调度。
3. 将 NodeQualityGate 拆分为确定性 Validator 与 Reviewer Agent 接口。
4. 将 ReportSynthesizer 限制为 Aggregator 输入契约。
5. 接入有界 retry/replan/degrade 循环、SSE 事件及计费生命周期。
6. 修复执行持久化的 claim/renew/complete 闭环和事件单写入问题。
7. 新建 `copilot-mcp-server`，完成动态注册、续约、注销、MCP 握手、策略筛选和代理调用。
8. 让角色 Toolkit 从 Registry 的受授权目录获取外部 MCP Tool；保持既有 Java Tool 不变。
9. 删除被新职责替代的重复编排路径，并运行分层验证。
