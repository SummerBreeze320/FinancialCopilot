# 金融智能投研系统生产运行时容量规划与运维手册 (Production Runtime Capacity & Operations)

## 1. 概述与核心调度架构

FinancialCopilot 运行时采用单体/集群双重弹性的分层准入与资源调度架构。为保障系统面对高并发金融研报生成场景下的稳定性，运行时通过以下核心组件构建了全局容量护栏：

```
                           [进站请求 HTTP / SSE]
                                    │
                                    ▼
                ┌──────────────────────────────────────┐
                │       RunAdmissionController         │
                │  - 会话串行化 (同一会话只允许1个运行)   │
                │  - 单用户并发上限 (maxActiveRunsPerUser) │
                │  - 全局运行与排队上限 (maxActive/Queued) │
                │  - 排队超时降级保护 (queueTimeout)    │
                └───────────────────┬──────────────────┘
                                    │ (准入)
                                    ▼
                ┌──────────────────────────────────────┐
                │        GlobalReadyScheduler          │
                │  - 跨租户/跨用户/跨Run公平轮询(RR)    │
                │  - 单Run内动态优先级就绪扫描 (EDF)     │
                │  - 单Run Agent并发配额 (maxAgents/Run)│
                └───────────────────┬──────────────────┘
                                    │ (派发)
                                    ▼
                 ┌────────────────────────────────────┐
                 │       LlmExecutionGateway          │
                 │  - 全局模型并发管控 (maxLlmConcurrency)│
                 │  - 调用超时熔断 (llmCallTimeout)     │
                 │  - 提供商级低基数错误计数           │
                 └────────────────────────────────────┘
```

---

## 2. 核心容量配比与协同规划 (Capacity Sizing)

投研任务具有重计算、高并发 I/O、以及多 Agent 拓扑编排的特征。若物理资源配比失衡，容易引发数据库连接池打满、模型网关超时或虚拟线程饥饿。建议按下述配比公式联动规划：

### 2.1 关键容量参数定义

| 参数名 (`copilot.runtime.*`) | 环境变量 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- |
| `max-active-runs` | `COPILOT_MAX_ACTIVE_RUNS` | `100` | 单节点最大允许同时处于活跃执行状态的 Run 数量 |
| `max-queued-runs` | `COPILOT_MAX_QUEUED_RUNS` | `1000` | 全局准入队列最大等待容量，超出立即返回 `503` |
| `max-active-runs-per-user` | `COPILOT_MAX_ACTIVE_RUNS_PER_USER` | `2` | 单用户最大允许同时活跃执行的 Run 数量 |
| `max-queued-runs-per-user` | `COPILOT_MAX_QUEUED_RUNS_PER_USER` | `5` | 单用户最大排队等待 Run 数量，超出立即返回 `429` |
| `max-agents-per-run` | `COPILOT_MAX_AGENTS_PER_RUN` | `3` | 单个拓扑图内部允许并发执行的最大 Agent 节点数 |
| `max-agent-concurrency` | `COPILOT_MAX_AGENT_CONCURRENCY` | `32` | 调度器持有的物理 Agent 执行并发许可总数 |
| `max-llm-concurrency` | `COPILOT_MAX_LLM_CONCURRENCY` | `16` | 大模型执行网关全局信号量上限 |
| `max-rag-concurrency` | `COPILOT_MAX_RAG_CONCURRENCY` | `32` | 向量与混合知识库检索全局并发许可数 |
| `llm-call-timeout` | `COPILOT_LLM_CALL_TIMEOUT` | `120s` | 单次 LLM 推理最长超时等待阈值 |
| `queue-timeout` | `COPILOT_QUEUE_TIMEOUT` | `5m` | 准入队列中未获调度的任务最长等待超时 |

### 2.2 核心容量协同计算公式

1. **Agent 与 LLM 网关配比**：
   $$MaxLlmConcurrency \approx 0.5 \times MaxAgentConcurrency$$
   - 典型投研 DAG 节点包含：数据获取（SQL/行情）、特征工程（纯计算）、以及模型分析（LLM）。并非所有并发 Agent 同时发起 LLM 调用，建议按 $1:2$ 比例配置。
2. **JDBC 数据库连接池协同 (HikariCP)**：
   $$HikariCP.maximumPoolSize \ge (MaxActiveRuns \times 0.2) + MaxAgentConcurrency + 10$$
   - 每个 Run 节点生命周期（租约延期、产物落库、断点保存）需占用短暂数据库连接。对于 32 Agent 并发的单节点，建议 HikariCP 最大连接池设为 **50 ~ 64**。
3. **内存与虚拟线程配置**：
   - Java 21 虚拟线程支持高密度 I/O 等待，但每个活跃 Run 的图结构与上下文快照（约 100KB ~ 500KB）常驻堆内存。100 个并发 Run 仅需约 50MB 上下文开销，JVM 堆内存建议预留 **2GB ~ 4GB**。

---

## 3. 本地轻量模式 vs 分布式集群模式

系统支持基于 Spring 条件装配的平滑部署模式切换：

### 3.1 本地单机开发模式 (`copilot.redis.enabled: false`)
- **适用场景**：本地单机调试、离线单元测试、轻量化边缘节点。
- **状态存储**：
  - `DagCheckpointStore` 降级为 `InMemoryDagCheckpointStore`；
  - `GraphRunRegistry` 运行句柄保存在单机本地内存；
  - 租约竞争与接管机制绕过数据库锁，直接在本地 JVM 线程池调度。
- **限制**：节点重启后无法恢复未完成的 Run；不支持水平扩容。

### 3.2 生产分布式集群模式 (`copilot.redis.enabled: true` + PostgreSQL)
- **适用场景**：多实例生产集群、公有云部署。
- **分布式工作机制**：
  - **分布式租约**：Worker 实例通过 `sys_node_attempt` 表的 `lease_until` 与 `worker_id` 抢占分布式节点尝试。当 Worker 心跳超时，其它 Worker 通过 `RuntimeMetrics.recordLeaseTakeover()` 自动接管孤儿节点。
  - **断点同步**：DAG 检查点落入 Redis 与 PostgreSQL，节点故障时其它实例可无损 resume。
  - **断线重连**：`sys_research_event` 按 `seq` 严格单调递增，支持前端凭借 `Last-Event-ID` 进行防丢、防重重放。

---

## 4. 低基数生产可观测性指标 (Runtime Metrics)

系统严格杜绝 `userId`、`conversationId` 或 `runId` 等高基数维度污染 Prometheus / Micrometer 时序库。已注册标准指标如下：

| 指标名称 | 类型 | Tags | 含义与观测目标 |
| :--- | :--- | :--- | :--- |
| `copilot.runtime.runs.active` | Gauge | 无 | 当前处于活跃运行态的 Run 数量 |
| `copilot.runtime.runs.queued` | Gauge | 无 | 准入队列中正在等待调度的 Run 数量 |
| `copilot.runtime.queue.wait` | Timer | 无 | 任务从入队到获得准入的等待耗时分布 |
| `copilot.runtime.nodes.duration` | Timer | `role` (如 `planner`, `fund_analyst`) | 各角色分析节点的真实执行耗时分布 |
| `copilot.runtime.nodes.cancelled` | Counter | 无 | 因用户取消或超时取消的节点总数 |
| `copilot.runtime.llm.active` | Gauge | 无 | 大模型网关当前并发请求数 |
| `copilot.runtime.llm.errors` | Counter | `provider`, `error` | 大模型调用失败计数（如 `DEEPSEEK:SocketTimeoutException`） |
| `copilot.runtime.leases.takeover` | Counter | 无 | 分布式 Worker 抢占接管超时租约的次数 |

---

## 5. 生产运维告警阈值配置建议 (Prometheus / Alertmanager)

```yaml
groups:
  - name: financial-copilot-runtime-alerts
    rules:
      # 1. 准入队列等待过长告警
      - alert: CopilotQueueWaitTooLong
        expr: histogram_quantile(0.95, sum(rate(copilot_runtime_queue_wait_seconds_bucket[5m])) by (le)) > 30
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "投研准入队列 P95 等待耗时超过 30 秒"
          description: "系统容量达到瓶颈，建议扩容实例或上调 max-active-runs。"

      # 2. 大模型提供商错误率告警
      - alert: CopilotLlmHighErrorRate
        expr: sum(rate(copilot_runtime_llm_errors_total[5m])) / (sum(rate(copilot_runtime_llm_active[5m])) + 0.001) > 0.05
        for: 3m
        labels:
          severity: critical
        annotations:
          summary: "大模型执行网关调用失败率高于 5%"
          description: "上游大模型 API 出现限流、网络超时或凭据失效，请检查 provider:error 维度日志。"

      # 3. 数据库连接池饱和告警
      - alert: CopilotDatabasePoolSaturation
        expr: hikaricp_connections_active{pool="HikariPool-1"} / hikaricp_connections_max{pool="HikariPool-1"} > 0.85
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "HikariCP 数据库连接池使用率超过 85%"
          description: "数据库事务耗时增加或并发 Run 导致连接池耗尽，需扩充连接池或排查慢查询。"

      # 4. 节点租约异常接管告警 (Worker 失联/崩溃)
      - alert: CopilotNodeLeaseTakeoverDetected
        expr: increase(copilot_runtime_leases_takeover_total[5m]) > 5
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "短时间内发生多次节点租约接管"
          description: "集群中可能有 Worker 发生 OOM、网络分区或长 GC，导致租约超时被抢占。"
```

---

## 6. 自动化压测与回归验证

在版本上线前，必须执行全链路并发与数据隔离压测脚本：

```bash
# 1. 运行自包含断言模式 (无需外部服务)
node scripts/load/research-concurrency.js --mock

# 2. 针对指定生产/预发节点执行真实 100 并发压测
node scripts/load/research-concurrency.js --url http://10.0.0.10:8080
```

压测脚本将自动断言：
1. **零跨租户/跨用户数据泄露**（严格校验返回文本不含任何其他用户的 Marker）；
2. **会话串行化**（同会话并发直接进入队列或隔离）；
3. **429 与 503 过载保护**响应符合配置阈值；
4. **SSE 断线重连与增量重放**严格单调递增无重复。
