# Fund Screening Service

基于 **LangChain + LangGraph** 驱动的高精度基金筛选 Agent 编译器，将用户的自然语言筛选需求转换为数据引擎可执行的 `fund-screening/v1` Command DSL。

系统将不可控的黑盒 Agent 拆解为确定性有向状态图（StateGraph）：具备清晰的状态契约、可观测 trace、三级动态路由、严格受限的双处结构化 LLM 决策、有界机械修复校验循环与 Redis 多轮 Checkpoint 持久化。

---

## 核心架构与能力全景

### 1. LangGraph StateGraph 状态流转图

```text
[START]
   │
   ▼
query_preprocess ──► pre_route_recall ──► route_classifier
                                                │
         ┌──────────────────────────────────────┼─────────────────────────┐
         │ (intent=EDIT & simple)               │ (route=direct, 0 LLM)   │ (route=simple, <=1 LLM) │ (route=full, 2 LLM)
         ▼                                      ▼                         ▼                         ▼
    simple_edit                            direct_build          simple_semantic_parse       pre_recall_plan (LLM 1)
         │                                      │                         │                         │
         │                                      │                         │                         ▼
         │                                      │                         │                  parallel_recall
         │                                      │                         │                         │
         │                                      │                         │                         ▼
         │                                      │                         │               candidate_adjudication (LLM 2)
         │                                      │                         │                         │
         │                                      │                         │                         ▼
         │                                      │                         │                   command_build
         │                                      │                         │                         │
         └──────────────────────────────────────┴─────────────────────────┴─────────────────────────┘
                                                │
                                                ▼
                                            validator
                                                │
                                  ┌─────────────┴─────────────┐
                    (repairable & attempt<2)    │ (clean)     │ (invalid or attempt>=2)
                                  ▼             ▼             ▼
                          mechanical_repair  command_finalize  clarification
                                  │             │             │
                                  └──►validator │             │
                                                ▼             ▼
                                              [END]         [END]
```

### 2. 三级分流机制 (Three-Level Routing)

1. **Direct 分流 (0 次 LLM)**:
   - 命中纯基金代码（如 `001938`、`161725.OF`）或实体库高置信精确匹配（score >= 0.95）。
   - 直接构建单实体/单代码契约 DSL，跳过所有语义解析与裁决。
2. **Simple 分流 (<= 1 次 LLM)**:
   - 针对单业务组且少于 4 个 AND 条件的常见筛选（如“规模大于10亿且成立超过5年”）。
   - 支持轻量单步语义解析（<= 1 次结构化 LLM 调用）或规则抽取，彻底淘汰硬编码 MVP。
3. **Full 分流 (严格 2 次 LLM)**:
   - 针对多组条件、复杂逻辑（`或者/OR`、`排除/NOT`、嵌套关系）。
   - **LLM Call 1 (`pre_recall_plan`)**: 调用 `ChatOpenAI.with_structured_output(RecallPlanOutput)`，将 query 拆解为 condition list。
   - **三路并行召回 (`parallel_recall`)**: 分别通过 PGVector (metric/scope) 与 Lexical (entity) 召回候选。
   - **LLM Call 2 (`candidate_adjudication`)**: 调用 `ChatOpenAI.with_structured_output(DecisionOutput)`，在受限候选池内完成裁决填参，杜绝幻觉。

### 3. 指标参数契约与端到端校验 (Parameter Contracts)

- **169 个指标 100% 参数覆盖**:
  - 全量指标库位于 `docs/temp/metrics.json`，覆盖率报告位于 `docs/temp/metric-parameter-coverage.json`。
  - 参数族系推导：
    - `f_risk_` 基准风险指标：必填 `windCode`, `startDate`, `endDate` (默认 `LATEST_TRADE_DATE`), `indexCode` (默认 `FUND_BENCHMARK`)。
    - `f_prt_`, `f_netasset_`, `f_holder_`, `f_style_` 报告期指标：必填 `reportDate` (默认 `LATEST_REPORT_DATE`)。
    - `f_return_`, `f_nav_`, `f_mmf_` 收益净值指标：必填 `endDate` (默认 `LATEST_TRADE_DATE`)。
- **端到端契约穿透**:
  - 从 CandidateContract → CandidatePool → DecisionOutput → CommandBuilder → MetricPredicate → Validator 全链路携带并校验参数。
  - 自动填充缺省默认参数（生成 `DEFAULT_METRIC_PARAMETER` 修复事件）。
  - 缺少必填参数或非法枚举值自动触发澄清分支，终止并提示用户。

### 4. 有界校验循环与机械修复 (Bounded Repair Loop)

- 针对系统可确定修复的问题（如负向回撤口径转换 `NEGATIVE_DRAWDOWN_STORAGE`、默认排序 `DEFAULT_GROUP_SORT`、默认参数填充 `DEFAULT_METRIC_PARAMETER`），进行自动化修复。
- 循环边界：最多执行 2 次修复尝试。若仍存在校验错误，安全转移到 `clarification` 节点并提供结构化修复建议，防止死循环。

### 5. 多轮 Checkpoint 与会话持久化 (Multi-turn Checkpoint)

- **无感状态恢复**: 用户在多轮编辑时，无需再次回传庞大的 `previousDsl`，仅需提供 `conversationId`，系统自动从 Checkpoint 恢复上一轮已 finalize 的 DSL。
- **原子修改节点 (`simple_edit`)**: 当检测到意图为简单修改（调整 limit、改变排序方向、删除非关键条件）时，直接对上轮 DSL 进行 AST 编辑，耗时 < 5ms。
- **Redis 持久化支持**: 实现 `RedisCheckpointSaver`，原生使用 Redis Hash 结构持久化 Checkpoint 元数据、通道值 Blobs 与 Writes。当 Redis 不可用时无缝自动回退到内存 `MemorySaver`。

### 6. HTTP / WebSocket 统一编排

- `POST /v1/fund-screening/commands` 与 WebSocket `/v1/fund-screening/ws` 统一调用相同的 StateGraph 编译执行管道。
- 统一向客户端发射 5 阶段生命周期事件：
  - `query_preprocess`
  - `query_analysis_and_recall`
  - `command_build`
  - `validator_and_repair`
  - `command_finalize`

---

## 快速上手与运行

### 1. 环境准备与依赖安装

```powershell
cd fund-screening-service
python -m pip install -e ".[dev]"
```

### 2. 运行自动化测试套件

```powershell
# 运行全量单元测试与 API 测试 (78 passed, 4 skipped)
pytest

# 运行特定模块测试
pytest -v tests/unit/test_structured_llm.py
pytest -v tests/unit/test_graph_routing.py
pytest -v tests/unit/test_metric_parameter_validation.py
pytest -v tests/unit/test_graph_checkpoint.py
pytest -v tests/api/test_commands.py
pytest -v tests/api/test_websocket.py
```

### 3. 指标参数全量推导与覆盖率报告生成

```powershell
python scripts/enrich_metric_parameters.py ../docs/temp/metrics.json ../docs/temp/metrics.json --report ../docs/temp/metric-parameter-coverage.json
```

输出示例：
```json
{
  "total_metrics": 169,
  "enriched_metrics": 169,
  "coverage_rate": 1.0,
  "by_parameter_count": { "2": 83, "3": 27, "1": 55, "4": 4 },
  "by_parameter_type": { "FUND_CODE": 169, "DATE": 145, "INDEX_CODE": 4 }
}
```

### 4. 运行真实依赖 Smoke Test

在具有本地真实 LLM（如 Codex Bridge）、PgVector、Redis 环境下：

```powershell
$env:RUN_REAL_FUND_SCREENING_TESTS = "1"
pytest -v tests/integration/test_real_graph_smoke.py
```

### 5. 启动 HTTP / WebSocket 服务

```powershell
python -m uvicorn app.main:app --host 127.0.0.1 --port 8810
```

---

## API 调用示例

### HTTP 单轮查询 (Direct 路由示例)

```powershell
$body = @{ query = "001938" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8810/v1/fund-screening/commands `
  -ContentType application/json -Body $body
```

### HTTP 多轮查询 (基于 conversationId)

```powershell
# 第一轮：创建筛选
$body1 = @{
  query = "筛选规模大于10亿且近三年收益大于50%的主动权益基金，取前20只"
  conversationId = "conv-session-1001"
} | ConvertTo-Json
$res1 = Invoke-RestMethod -Method Post -Uri http://localhost:8810/v1/fund-screening/commands `
  -ContentType application/json -Body $body1

# 第二轮：修改数量（无需回传 previousDsl，直接通过 conversationId 恢复）
$body2 = @{
  query = "改取前50只"
  conversationId = "conv-session-1001"
} | ConvertTo-Json
$res2 = Invoke-RestMethod -Method Post -Uri http://localhost:8810/v1/fund-screening/commands `
  -ContentType application/json -Body $body2
```

### WebSocket 实时流式调用

连接 `ws://localhost:8810/v1/fund-screening/ws`，发送：

```json
{
  "query": "筛选低回撤主动权益基金",
  "conversationId": "ws-conv-2002"
}
```

客户端将按序接收到各个节点的生命周期事件（`stage_started`, `stage_completed`）以及最终 `finalized` DSL 结果。

