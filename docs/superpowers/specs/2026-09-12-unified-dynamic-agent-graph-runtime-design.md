# 统一动态 Agent Graph Runtime 设计

## 1. 目标

将投研系统的同步、SSE、恢复和人工控制统一到一套动态 Graph Runtime。生产链路不再经过 `TaskDecomposer`、`ExecutionPlan`、`SubTask`、`LegacyPlanAdapter`、`ResearchBlackboard` 或串行 `for step` 执行器。

外部 HTTP 执行入口与内部执行模型一并硬切换。系统使用 Java 21 虚拟线程、依赖满足即调度的 DAG、强类型 Artifact、有限轮次 AgentScope ReAct Planner/Agent、动态 GraphPatch、资源限流、节点超时和可恢复 Checkpoint。

## 2. 范围

本次实现包含：

- 同步与 SSE 共用统一 Graph 执行入口。
- Planner 直接生成初始 `ExecutionGraph`。
- Planner 根据节点产物生成 GraphPatch。
- 节点级 Typed Artifact 和显式 InputBinding。
- 每次运行完全隔离的运行上下文。
- Java 21 虚拟线程、资源配额、优先级、取消、超时、重试和失败策略。
- 完整 Graph、Artifact、节点状态和版本的 Redis Checkpoint。
- 登录用户隔离的查询、取消和恢复接口。
- 节点事件、Graph 更新事件、内容分片和运行终态 SSE。
- Planner 和需要自主选择工具的 Agent 的有限轮次 ReAct。
- 删除旧内部执行入口、适配器及对应兼容测试。

本次不包含：

- A2A 协议或跨服务 Agent 发现。
- 前端 Graph 可视化页面。
- 分布式队列或多进程任务抢占。
- 人工修改任意 Graph 结构；人工控制仅包含查询、取消和恢复。

## 3. 统一入口

`FinancialResearchWorkflow` 保留为领域门面，但只负责鉴权后上下文组装与结果映射。所有入口最终调用一个方法：

```java
GraphRunHandle run(GraphRunRequest request);
```

`GraphRunRequest` 包含：

- `runId`
- `userId`
- `sessionId`
- `prompt`
- `enableThinking`
- `UserInvestmentProfile`
- `Consumer<LlmResponse> usageConsumer`
- `RunMode`，取值 `SYNC` 或 `STREAM`

`GraphRunHandle` 暴露：

- `Flux<ResearchStreamEvent> events()`
- `CompletableFuture<GraphRunResult> completion()`
- `cancel(String reason)`

同步接口等待 `completion()` 并返回最终报告；SSE 接口直接返回 `events()`。两者使用相同 Planner、Graph、节点执行器、ArtifactStore 和计费回调。

HTTP 统一使用 `POST /api/v1/research/runs`，通过 `Accept: application/json` 或
`Accept: text/event-stream` 选择同步结构化结果或 SSE。旧的 `/chat`、`/workflow/execute` 和
`/chat/pipeline/stream` 执行入口已删除；用户身份只从认证上下文获取。

## 4. Graph Planner

`GraphPlanner` 是唯一初始建图入口，同时实现运行时 `RePlanAdvisor`。工作流不再调用 `TaskDecomposer`。

Planner 使用最多 4 轮的有限 ReAct：

1. `Reason`：识别目标、资产类型、证据要求和可并行分支。
2. `Act`：选择一个只读规划工具。
3. `Observation`：记录结构化工具结果。
4. `Decide`：继续检索或输出 GraphPlan。

规划工具限定为：

- `MetricRAGTool`
- `SkillRegistryTool`
- `CapabilityRegistryTool`
- `FinancialDocumentSearchTool`
- `MarketMemoryTool`

Planner 输出结构化 `GraphPlan`，再由确定性校验器构造成 `ExecutionGraph`。校验器拒绝环、无显式输入绑定的非根节点，以及没有实际 AgentScope 角色的 taskType。模型不可用、输出无效或达到最大轮次时本次规划失败，不使用自制规则 Planner 兜底。

Planner 只描述 WHAT、WHY、依赖、输入和输出契约，不指定 Agent 内部的原子工具调用顺序。

## 5. Graph 与节点模型

`ExecutionGraph` 保存：

```java
String graphId;
long revision;
Map<String, GraphNode> nodes;
Map<String, Set<String>> upstream;
Map<String, Set<String>> downstream;
```

`GraphNode` 保存：

- 稳定且在单次 Graph 内唯一的 `nodeId`
- `taskType` 和显示名称
- `List<InputBinding> inputs`
- `ArtifactType outputType`
- 参数
- 超时
- FailurePolicy
- 最大重试次数
- 资源需求
- 优先级

节点状态为：

```text
PENDING, READY, RUNNING, SUCCEEDED, FAILED,
SKIPPED, CANCELLED, TIMEOUT
```

逻辑 Wave 仅用于布局，不参与调度。

## 6. Typed Artifact 与 InputBinding

Artifact 保持以下核心结构：

```java
Artifact<T>(
    String id,
    ArtifactType type,
    String producerNodeId,
    T payload,
    ArtifactMetadata metadata,
    EvidenceContract evidenceContract
)
```

新增明确的数据绑定：

```java
InputBinding(
    String name,
    String producerNodeId,
    ArtifactType expectedType,
    String path,
    boolean required
)
```

`path` 使用项目内最小字段访问语法，例如 `$.funds`、`$.topCandidates`；不引入新的 JSONPath 依赖。空路径或 `$` 表示整个 payload。

Runtime 在节点进入 READY 前验证必需绑定：生产节点成功、Artifact 存在、类型一致、字段可读取。Agent 通过 `NodeInput` 按绑定名读取数据，不再调用全局 `findFirstByType()` 选择不确定产物。

ArtifactStore 按 `nodeId` 和 `artifactId` 索引，并为同一次 Run 独占。全局上下文只存不可变的用户、会话、Prompt、画像和计费信息，不再保存 `ResearchBlackboard`。

## 7. DagRunContext 与并发隔离

每次运行创建独立 `DagRunContext`，包含：

- runId、userId、sessionId
- ExecutionGraph
- ArtifactStore
- 节点状态表
- PriorityReadyQueue
- CancellationToken
- 活跃节点计数
- NodeEventBus
- 完成 Future

`DagRuntime` 本身保持无 Run 状态，仅持有共享的虚拟线程 Executor、资源管理器、CheckpointStore、Planner 和 NodeExecutor。不同请求不共享 Ready Queue、状态或 Artifact。

空 Graph 立即完成。Runtime 关闭时通过 Spring 生命周期关闭共享 Executor。

## 8. 调度和资源限制

Runtime 使用依赖驱动调度：根节点进入 READY；每个节点完成后只检查直接下游；所有控制依赖和必需数据绑定满足后立即调度。

使用 Java 21 `Executors.newVirtualThreadPerTaskExecutor()`。资源限制仍由信号量控制，至少包括：

- Agent：8
- LLM：4
- DPU：10
- RAG：20
- MCP：10

节点可声明多个资源需求。Runtime 必须按固定资源类型顺序原子获取；任何一个资源不足时不占用部分配额，节点留在 Ready Queue。

## 9. Timeout、取消与 FailurePolicy

每个节点执行受 `GraphNode.timeout` 约束。超时后取消节点 Token、尝试中断虚拟线程并设置 `TIMEOUT`。

FailurePolicy 行为：

- `FAIL_FAST`：终止整个 Run，未开始节点改为 CANCELLED。
- `CONTINUE`：保存降级 Artifact，下游可以继续，并标记证据不完整。
- `RETRY`：在最大次数内重新进入 READY；用量已发生的 LLM 调用仍按实际 usage 结算。
- `FALLBACK`：执行显式 fallback 并保存 partial Artifact。
- `OPTIONAL`：标记 SKIPPED，下游的可选绑定为空。

客户端断开 SSE 后停止向客户端发事件。已经开始的计费 LLM 调用继续读取至最终 usage 并结算；尚未开始的节点取消。

## 10. 动态 GraphPatch

GraphPatch 包含 `baseRevision`、操作列表和 reason。支持：

- ADD_NODE
- REMOVE_NODE
- ADD_EDGE
- REMOVE_EDGE
- UPDATE_NODE
- SKIP_NODE
- RETRY_NODE

应用流程：

1. 校验 baseRevision。
2. 在 Graph 副本上执行全部操作。
3. 检查节点唯一性、边引用、环、运行中节点修改限制和 InputBinding。
4. 校验通过后一次性替换 Graph 状态并增加 revision。
5. Runtime 对状态表和活跃计数执行同一个临界区内的协调更新。
6. 发布 `graph_updated`。

已经 RUNNING 或进入终态的节点不能删除或修改执行契约。Planner 可以新增节点、调整尚未运行的节点、跳过 PENDING/READY 节点，或明确重试 FAILED/TIMEOUT 节点。

## 11. Agent 局部 ReAct

需要自主选工具的分析节点使用最多 5 轮局部 ReAct：

```text
Reason -> ToolCall -> Observation -> Evidence Check -> Decide
```

每次 ToolCall 只能从该 Agent 注册的能力表中选择。达到最大轮次、证据充分或没有可用工具时结束。Agent 输出一个符合节点 output contract 的 Artifact。

确定性工具节点，例如单次数据库筛选或指标计算，不包装成 ReAct 循环。这样避免为简单操作增加不必要的 LLM 调用。

## 12. 事件与 SSE

NodeEventBus 是 Runtime 的唯一事件来源。事件至少包括：

- `graph_initialized`
- `node_ready`
- `node_started`
- `node_completed`
- `node_failed`
- `graph_updated`
- `content_chunk`
- `run_completed`
- `run_failed`
- `run_cancelled`

所有事件包含 `runId`；节点事件包含 `nodeId`、状态、时间和关联 Artifact ID；Graph 更新包含 revision 和 Patch 摘要。

最终合成节点可以流式发布 `content_chunk`，同时在完成时保存完整 `FINAL_REPORT` Artifact。同步调用忽略中间事件，只读取最终 Artifact。

## 13. Checkpoint 与恢复

每个节点终态和每次 GraphPatch 后保存 Checkpoint，内容包括：

- runId、userId、sessionId
- 完整 ExecutionGraph 和 revision
- 所有节点状态及重试次数
- 完整 Artifact，包括 payload、metadata 和 evidence contract
- 不包含密钥的运行上下文
- 保存时间和过期时间

Redis Key 同时包含 userId 和 runId。恢复时必须校验当前登录用户是 Run 所有者。RUNNING、READY 状态恢复成 PENDING，SUCCEEDED 和 SKIPPED 不重复执行；Runtime 重建依赖和数据绑定后重新调度。

新增控制接口：

- `GET /api/v1/research/runs/{runId}`：查询状态和 Graph 摘要。
- `POST /api/v1/research/runs/{runId}/cancel`：取消当前用户的运行。
- `POST /api/v1/research/runs/{runId}/resume`：从 Checkpoint 恢复。

不存在、已过期或不属于当前用户的 Run 返回 404，避免泄露其他用户的 runId。

## 14. 计费与用户隔离

用户 ID 只从认证上下文取得。GraphRunRequest 使用已经过隔离处理的 session key。Checkpoint、Artifact、事件和 Run Registry 都以 userId 为所有权边界。

每次真实 LLM 响应继续通过现有 usageConsumer 立即结算。Planner 和局部 ReAct 的所有 LLM 调用都必须携带同一 usageConsumer；Mock、确定性规划和规则 fallback 不收费。

余额不足、数据库扣费失败或真实供应商未返回 usage 时，该次调用失败，并按节点 FailurePolicy 处理；不得生成免费真实模型结果。

## 15. 删除旧设计

完成新入口迁移后删除：

- `TaskDecomposer`
- `ExecutionPlan`
- `SubTask`
- `LegacyPlanAdapter`
- `BlackboardAdapter`
- `ResearchBlackboard`
- 流式 `for step` 执行器
- 只验证旧执行模型的测试

Agent 中仅供旧入口使用的 `executeStep`、字符串黑板读写和旧式重载一并删除。外部 Controller 不直接依赖以上类型。

## 16. 验证标准

实现完成必须满足：

1. 同一个业务请求通过同步和 SSE 两种入口产生相同 Graph 结构和最终 Artifact。
2. 两个并发 Run 不共享节点、队列、Artifact 或事件。
3. A 完成后其下游立即运行，不等待无关慢节点。
4. 相同类型的多个 Artifact 通过 InputBinding 准确选择。
5. Planner 能生成并行分支，并在空结果、单候选和证据不足时应用合法 Patch。
6. REMOVE、SKIP、RETRY Patch 不会破坏活跃计数或造成永不完成。
7. 节点超时进入 TIMEOUT，并按策略重试或终止。
8. Redis 恢复不重复执行成功节点，并能读取恢复后的上游 Artifact。
9. 非 Run 所有者不能查询、取消或恢复。
10. SSE 接收真实节点事件和 Graph revision，不再由 `for step` 模拟。
11. Planner、比较和合成产生的所有真实 LLM usage 都完成计费。
12. 项目中不再存在生产代码对旧执行模型的引用。

## 17. 迁移顺序

1. 修复 Runtime 的 Run 隔离、空图、超时和 Patch 协调。
2. 建立 InputBinding 和完整 Checkpoint。
3. 让 GraphPlanner 成为唯一初始建图入口。
4. 将 Agent 改为 NodeInput/Artifact 契约，并加入必要的有限 ReAct。
5. 将同步和 SSE 接入统一 `run()`。
6. 增加 Run 查询、取消和恢复入口。
7. 删除旧执行模型和兼容代码。
8. 运行单元、并发、恢复、控制器和真实 PostgreSQL/Redis 集成验证。
