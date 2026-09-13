# 对话持久化设计

日期：2026-09-13

状态：已批准
关联设计：[统一动态 Agent 图运行时设计](./2026-09-12-unified-dynamic-agent-graph-runtime-design.md)

## 1. 背景与目标

当前研究流程只在成功结束后把 USER 和 ASSISTANT 文本写入 Redis 短期记忆，并把最终报告写入 long_term_memory。Redis 数据约 30 分钟后过期；失败、取消和运行中的对话不会形成完整记录。DAG Checkpoint 保存的是运行恢复状态，有 24 小时有效期，也不能作为用户对话历史的事实来源。

本设计补齐持久化对话能力：

- PostgreSQL 保存用户可见的对话、消息和 Agent 工具审计，作为唯一事实来源。
- 同一对话可以包含多次研究运行，每次运行通过 runId 独立追踪。
- 成功、失败、取消都留下明确的消息状态。
- AgentScope ReAct 的工具调用保存名称、状态、耗时和产物引用，支持排障与审计。
- Redis 短期记忆继续作为可重建的提示词缓存。
- DAG Checkpoint 只负责短期恢复，不承担对话历史职责。
- 不保存模型隐藏推理、系统提示词或完整敏感工具结果。

## 2. 当前实现差距

1. FinancialResearchWorkflow.recordCompletion 只在成功回调中写记忆，失败和取消没有持久化记录。
2. ShortTermMemoryService 使用 Redis List，键为 shortterm:session:<sessionId>，TTL 为 30 分钟。
3. long_term_memory 只有 session_id、content 和 created_at，内容是最终报告，无法表达消息顺序、角色、运行状态和错误。
4. refined_fact 是从报告提炼的派生事实，不是原始会话记录。
5. RedisDagCheckpointStore 的键为 copilot:dag:checkpoint:<userId>:<runId>，TTL 为 24 小时，数据面向图恢复。
6. AgentScopeInvocation 只在内存中持有最终 Msg 和 ToolResultBlock 映射，没有工具调用历史。
7. 目前没有按用户查询会话列表、分页读取消息或查看单次运行工具审计的接口。

## 3. 核心边界

持久化数据分为四层：

| 层 | 存储 | 职责 | 是否事实来源 |
| --- | --- | --- | --- |
| 对话与消息 | PostgreSQL | 用户可见历史、顺序、状态、运行关联 | 是 |
| 工具审计 | PostgreSQL | AgentScope 工具调用摘要、耗时、产物引用 | 是 |
| 短期上下文 | Redis | 最近消息的 Prompt 缓存，可从 PostgreSQL 重建 | 否 |
| 运行恢复 | Redis Checkpoint | 图结构、节点状态、Artifact 和恢复游标 | 否 |
| 语义记忆 | PostgreSQL long_term_memory / refined_fact | 报告摘要与提炼事实 | 否，属于派生数据 |

SSE 内容分片只用于实时传输。系统在运行开始时创建一条 RUNNING 的 ASSISTANT 消息，在结束时一次性写入合并后的最终内容，不逐块保存流式分片。

### 3.1 可配置持久化模式

新增配置：

    copilot:
      conversation:
        persistence-enabled: ${CONVERSATION_PERSISTENCE_ENABLED:true}

默认值为 true。生产环境默认保存完整对话；测试或临时环境可以显式设置 CONVERSATION_PERSISTENCE_ENABLED=false，避免产生大量对话与工具审计数据。

关闭后只停用 research_conversation、conversation_message 和 agent_tool_audit 的读写，以下能力继续运行：

- 每次请求仍生成 conversationId 和 runId，统一 Graph 入口与 AgentScope ReAct 执行方式不变；
- Redis 短期记忆继续按用户隔离的 sessionKey 保存；
- long_term_memory 和 refined_fact 继续保存语义记忆；
- Redis DAG Checkpoint 继续保存图、节点、Artifact 和恢复信息；
- 计费、用户隔离、取消和 Checkpoint 恢复继续生效。

关闭模式下不创建用户消息、助手消息和工具审计记录，GraphRunRequest 与 DagCheckpoint 的 assistantMessageId 允许为空。传入 conversationId 时，系统只把它作为当前用户隔离记忆的逻辑标识，不执行数据库所有权校验；sessionKey 仍包含认证 userId，因此不同用户使用相同 conversationId 也不会共享记忆。

历史列表、消息和工具审计查询接口返回 HTTP 503，错误码为 CONVERSATION_PERSISTENCE_DISABLED。运行状态、取消和恢复接口继续以 userId 加 runId 的 Checkpoint/Run Registry 所有权边界工作。切换配置只影响切换后的请求，不迁移或删除已有历史。

### 3.2 可配置 Redis 存储模式

新增独立配置：

    copilot:
      redis:
        enabled: ${REDIS_ENABLED:true}

默认值为 true。设置 REDIS_ENABLED=false 后，应用不读取或写入 Redis，且以下组件在启动时切换为进程内实现：

- ShortTermMemoryService 使用 InMemoryShortTermMemoryStore，保留 30 分钟 TTL、消息顺序和 token 裁剪语义；
- LongTermMemoryService 使用 InMemoryLongTermMemoryCache，长期记忆和 refined_fact 仍以 PostgreSQL 为事实来源；
- DagCheckpointStore 使用 InMemoryDagCheckpointStore，继续保存当前进程中的图、节点状态和 Artifact。

RedisConfig、RedisShortTermMemoryStore、RedisLongTermMemoryCache 和 RedisDagCheckpointStore 只在 enabled=true 时装配。业务服务不再直接依赖 RedisTemplate，而是依赖各自的存储接口。模式在应用启动时确定，不在运行期间自动切换。

内存模式的数据只在当前 JVM 内有效，应用重启后短期记忆缓存和 DAG Checkpoint 会丢失，也不能在多个应用实例之间共享。PostgreSQL 中的长期记忆、提炼事实以及启用的完整对话记录不受影响。内存实现必须按 TTL 惰性清理，并限制最多 1000 个 session/run，超限时淘汰最早访问项，避免测试进程无限增长。

两个开关互相独立：

| conversation.persistence-enabled | redis.enabled | 对话历史 | 短期记忆 | DAG Checkpoint | 长期记忆 |
| --- | --- | --- | --- | --- | --- |
| true | true | PostgreSQL | Redis | Redis | PostgreSQL，Redis 缓存 |
| true | false | PostgreSQL | JVM 内存 | JVM 内存 | PostgreSQL，JVM 缓存 |
| false | true | 不保存 | Redis | Redis | PostgreSQL，Redis 缓存 |
| false | false | 不保存 | JVM 内存 | JVM 内存 | PostgreSQL，JVM 缓存 |

## 4. 标识与所有权

- conversationId：服务端生成 UUID，表示一个用户对话。
- runId：一次图执行的 UUID；同一 conversationId 可以关联多个 runId。
- userId：只从认证上下文读取，客户端不能指定或覆盖。
- sequenceNo：对话内严格递增的消息序号。
- 内部记忆键：继续通过 SecurityUtils.sessionKey(userId, conversationId) 生成，避免客户端会话标识直接成为 Redis 键。

所有查询和更新都必须同时携带 user_id 与目标 ID。不存在的资源和其他用户的资源统一返回 404，避免通过响应差异枚举数据。

## 5. 数据模型

### 5.1 research_conversation

    CREATE TABLE research_conversation (
        id UUID PRIMARY KEY,
        user_id BIGINT NOT NULL,
        title VARCHAR(200) NOT NULL,
        status VARCHAR(20) NOT NULL,
        last_message_at TIMESTAMPTZ NOT NULL,
        created_at TIMESTAMPTZ NOT NULL,
        updated_at TIMESTAMPTZ NOT NULL,
        deleted_at TIMESTAMPTZ NULL
    );

    CREATE INDEX idx_conversation_user_last_message
        ON research_conversation(user_id, last_message_at DESC, id DESC)
        WHERE deleted_at IS NULL;

status 仅允许 ACTIVE 和 ARCHIVED。首版标题取第一条用户消息清理空白后的前 40 个 Unicode 字符；不额外调用模型生成标题。

### 5.2 conversation_message

    CREATE TABLE conversation_message (
        id BIGSERIAL PRIMARY KEY,
        conversation_id UUID NOT NULL REFERENCES research_conversation(id),
        user_id BIGINT NOT NULL,
        run_id UUID NOT NULL,
        sequence_no BIGINT NOT NULL,
        role VARCHAR(20) NOT NULL,
        status VARCHAR(20) NOT NULL,
        content TEXT NOT NULL DEFAULT '',
        error_code VARCHAR(80) NULL,
        error_message VARCHAR(500) NULL,
        metadata JSONB NOT NULL DEFAULT '{}',
        created_at TIMESTAMPTZ NOT NULL,
        completed_at TIMESTAMPTZ NULL,
        UNIQUE(conversation_id, sequence_no),
        UNIQUE(user_id, run_id, role)
    );

role 首版只允许 USER 和 ASSISTANT。status 允许 RUNNING、COMPLETED、FAILED 和 CANCELLED。USER 消息创建后直接为 COMPLETED；ASSISTANT 消息先为 RUNNING，再进入终态。

metadata 只保存稳定的展示信息，例如 graphRevision、artifactIds、model 和 usage 汇总。大对象正文继续保存在 Artifact 存储中。

### 5.3 agent_tool_audit

    CREATE TABLE agent_tool_audit (
        id BIGSERIAL PRIMARY KEY,
        conversation_id UUID NOT NULL REFERENCES research_conversation(id),
        assistant_message_id BIGINT NOT NULL REFERENCES conversation_message(id),
        user_id BIGINT NOT NULL,
        run_id UUID NOT NULL,
        node_id VARCHAR(128) NOT NULL,
        agent_name VARCHAR(128) NOT NULL,
        tool_call_id VARCHAR(200) NOT NULL,
        tool_name VARCHAR(200) NOT NULL,
        status VARCHAR(20) NOT NULL,
        arguments JSONB NOT NULL DEFAULT '{}',
        result_summary VARCHAR(1000) NULL,
        result_hash VARCHAR(128) NULL,
        artifact_ids JSONB NOT NULL DEFAULT '[]',
        started_at TIMESTAMPTZ NOT NULL,
        completed_at TIMESTAMPTZ NULL,
        duration_ms BIGINT NULL,
        error_code VARCHAR(80) NULL,
        error_message VARCHAR(500) NULL,
        UNIQUE(user_id, run_id, tool_call_id)
    );

status 允许 RUNNING、SUCCEEDED、FAILED 和 CANCELLED。arguments 必须先经过字段级脱敏；令牌、密钥、账号凭据和大段原始结果不得落库。

## 6. 消息生命周期

### 6.1 新运行

1. Controller 从认证上下文取得 userId。
2. 如果请求未带 conversationId，创建 research_conversation；如果已带，按 userId 校验其存在且为 ACTIVE。
3. 在一个数据库事务中：
   - 锁定对话记录并分配连续 sequenceNo；
   - 插入 COMPLETED 的 USER 消息；
   - 插入 RUNNING 的 ASSISTANT 占位消息；
   - 更新 last_message_at。
4. 事务提交成功后才调用统一 Graph 入口，避免模型已经消费但用户消息未保存。
5. GraphRunRequest 携带 conversationId、runId、assistantMessageId 和隔离后的 sessionKey。

### 6.2 成功

1. Graph 返回最终 Artifact。
2. 将 ASSISTANT 消息更新为 COMPLETED，写入最终文本和稳定 metadata。
3. 事务提交后，将最近消息投影到 Redis 短期记忆。
4. 异步或容错地写入 long_term_memory 和 refined_fact。
5. 释放运行态资源；Checkpoint 可按现有策略保留到 TTL 或主动删除。

### 6.3 失败与取消

- 失败将 ASSISTANT 消息更新为 FAILED，保存稳定 error_code 和经过清理的用户可见错误。
- 取消将其更新为 CANCELLED。
- 已产生的工具审计保持原状态；尚处于 RUNNING 的记录批量转为 FAILED 或 CANCELLED。
- SSE 客户端断开且服务端确认终止运行时，按 CANCELLED 处理。
- 不保存未完成的 SSE 内容分片，避免恢复时把半段报告误认为正式回答。

### 6.4 恢复和幂等

恢复同一个 runId 时复用原有 ASSISTANT 消息，不再插入 USER 或 ASSISTANT 消息。工具审计以 userId、runId、toolCallId 唯一约束执行 upsert。消息终态更新使用条件更新，仅允许 RUNNING 进入终态；重复完成请求不会生成重复历史。

如果用户对同一对话发起新问题，则生成新的 runId 和两条新消息。

## 7. AgentScope 工具审计

AgentScopeAgentFactory 继续是所有 ReAct Agent 的唯一创建位置。审计通过 AgentScope 的消息块和调用上下文采集：

1. 收到 ToolUseBlock 时插入或更新 RUNNING 记录。
2. 收到对应 ToolResultBlock 时更新为 SUCCEEDED 或 FAILED。
3. durationMs 从工具开始到结果返回计算，包含 AgentScope 调度和工具执行时间。
4. Artifact 工具只保存 artifactId 列表和简短结果摘要。
5. 工具返回值只保存长度受限、脱敏后的摘要和哈希，完整内容仍由 Artifact 或业务存储管理。
6. Planner、业务 Agent 和 Replanner 使用相同审计通道。
7. 审计写入失败不改变节点业务结果，但必须记录指标并进入有限重试。

明确不持久化以下内容：

- 模型隐藏思维链或内部推理 token；
- system prompt、开发者提示或内部安全策略；
- 完整密钥、Cookie、认证头和支付凭据；
- 可通过 Artifact 引用获取的大体积工具结果；
- 每次 LLM 内部中间草稿。

## 8. 服务与模块职责

### copilot-domain

新增 Conversation、ConversationMessage、AgentToolAudit 领域模型，以及 ConversationPort 和 AgentToolAuditPort。领域层定义状态迁移和所有权条件，不依赖 MyBatis、Redis 或 AgentScope 类型。

### copilot-data-engine

新增 PostgreSQL PO、Mapper 和端口实现，负责事务、游标分页、行级用户条件、幂等更新和批量终止工具审计。数据库迁移加入现有 schema 初始化链路。

### copilot-agent-core

新增：

- ConversationService：创建或校验对话、开始运行、完成、失败、取消和缓存投影。
- RunAuditContext：只携带当前 userId、conversationId、runId、assistantMessageId 和 nodeId。
- AgentToolAuditSink：把 AgentScope ToolUseBlock 与 ToolResultBlock 转换为领域审计事件。

FinancialResearchWorkflow 只负责 Graph 规划与执行，不再在成功回调中直接拼接 USER:/ASSISTANT: 并写记忆。

### copilot-app

统一研究入口在调用工作流之前开始消息生命周期，在成功、失败和取消路径完成消息状态。历史查询 Controller 只通过 ConversationService 访问数据，不直接访问 Mapper。

## 9. API 设计

现有统一入口保持一条执行路径：

    POST /api/v1/research/runs

请求体新增可选 conversationId；未提供时创建新对话。响应和 SSE 初始事件都返回 conversationId 与 runId。

新增查询接口：

- GET /api/v1/conversations?cursor=<opaque>&limit=<1..100>
- GET /api/v1/conversations/{conversationId}/messages?beforeSequence=<n>&limit=<1..100>
- GET /api/v1/research/runs/{runId}/tool-audits?cursor=<opaque>&limit=<1..100>
- POST /api/v1/conversations/{conversationId}/archive

列表按 last_message_at、id 稳定倒序；消息按 sequence_no 倒序分页，客户端可反转后展示。游标由服务端编码，客户端不依赖数据库主键结构。

## 10. 一致性与故障策略

- 开始消息事务失败：不启动 LLM，不发生计费。
- Graph 成功但完成消息更新暂时失败：保留 Checkpoint 和最终 Artifact，按 runId 执行有限补偿重试；接口返回明确失败，不能声称已持久化成功。
- Redis 写入失败：PostgreSQL 历史仍有效；下次读取时从 PostgreSQL 重建短期缓存。
- 语义记忆提炼失败：不影响用户可见消息，记录可重试任务或指标。
- 工具审计失败：不改变 Agent 节点结果，记录指标并有限重试。
- billing、message 和 Graph 无法组成跨存储全局事务；以 runId 作为关联和补偿键，禁止用重复执行模型来修复消息写入。
- 返回客户端的错误消息必须脱敏，内部异常堆栈不写入 conversation_message。

## 11. 并发控制

同一 conversationId 可以并发发起不同 runId。开始事务对 conversation 行加锁后分配 sequenceNo，确保消息顺序唯一。由于两个运行完成次序可能不同，展示顺序以消息创建 sequenceNo 为准，状态独立更新。

归档操作只阻止新运行，不中断已经开始的运行。已经开始的运行仍可进入终态。

## 12. 缓存与上下文重建

ShortTermMemoryService 改为缓存适配器：

1. 优先读取 Redis 最近消息。
2. 缓存缺失时按 userId 和 conversationId 从 PostgreSQL 读取最近 N 条 COMPLETED 消息。
3. 按 token 预算裁剪后写回 Redis，TTL 保持 30 分钟。
4. FAILED 和 CANCELLED 消息默认不进入模型上下文，但仍在历史 API 中展示。
5. RUNNING 消息不进入后续请求上下文。

缓存键继续使用隔离后的 sessionKey，不使用裸 conversationId。

## 13. 数据保留与删除

首版对话默认长期保留。archive 只改变状态。未来删除接口采用软删除，后台在 30 天后级联硬删除消息与工具审计，并删除相关 Redis 缓存；实现删除接口前不开放该行为。

用户可见对话内容不进入通用日志。首版不提供管理员读取消息正文的 API。

## 14. 可观测性

新增不含正文的指标：

- conversation_start_total
- conversation_complete_total
- conversation_failed_total
- conversation_cancelled_total
- conversation_persistence_latency
- agent_tool_audit_write_failure_total
- conversation_cache_rebuild_total

日志只记录 userId 的不可逆摘要、conversationId、runId、messageId、nodeId、toolName 和状态，不记录 prompt、回答正文或工具参数。

## 15. 验收标准

1. 用户刷新或服务重启后仍能分页读取自己的完整成功对话。
2. 失败和取消运行各有一条对应终态的 ASSISTANT 消息。
3. 其他用户使用 conversationId、runId 或消息游标均只能得到 404。
4. 同一 runId 恢复不会重复插入用户消息、助手消息或工具审计。
5. 同一对话并发运行时 sequenceNo 唯一且稳定。
6. Planner、业务 Agent 和 Replanner 的工具名、状态、耗时及 Artifact 引用可以按 runId 查询。
7. 数据库中不存在隐藏推理、system prompt、密钥和完整大体积工具结果。
8. Redis 数据过期后可以从 PostgreSQL 重建短期上下文。
9. PostgreSQL 短暂失败时不会在用户消息落库前启动 LLM。
10. 同步和 SSE 入口共享相同的消息生命周期与状态语义。
11. 现有用户隔离、计费、Checkpoint 恢复和统一 Graph 入口测试继续通过。
12. long_term_memory 和 refined_fact 明确作为派生数据，删除它们不会损坏原始对话历史。
13. persistence-enabled=false 时不写三张对话表，但短期记忆、长期记忆、提炼事实、计费和 DAG Checkpoint 仍正常工作。
14. persistence-enabled=false 时历史查询返回 CONVERSATION_PERSISTENCE_DISABLED，运行状态、取消和恢复仍可使用。
15. redis.enabled=false 且 Redis 地址不可达时应用仍能启动，短期记忆、长期记忆缓存和 Checkpoint 均使用进程内实现。
16. Redis 关闭模式保持 TTL、token 裁剪和用户隔离，但应用重启后内存态短期记忆与 Checkpoint 按预期丢失。
17. 两个开关的四种组合均有配置装配测试，且不会意外调用被禁用的存储后端。

## 16. 不在首版范围内

- 保存或展示模型隐藏思维链；
- 保存每个 SSE token 或内容块；
- 对话全文检索、向量检索和跨对话引用；
- 对话分享、多成员协作和管理员内容后台；
- 对工具完整返回值做重复存储；
- 使用模型自动生成会话标题；
- 永久保存 DAG Checkpoint。

## 17. 实施约束

- 完全沿用统一 Graph 入口，不恢复旧 Plan、dependsOn 或 for 循环执行设计。
- 每个业务 Agent 仍由 AgentScopeAgentFactory 创建为 AgentScope ReActAgent。
- 所有持久化接口先以领域端口表达，再由 data-engine 实现。
- schema 变更、端口实现、服务生命周期、AgentScope 审计和 API 查询分别提供可独立验证的测试。
- 完成书面评审后，再产出逐文件、逐测试、逐提交的执行计划。
