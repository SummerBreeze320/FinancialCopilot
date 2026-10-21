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

### 3. 指标参数与结果契约 (Parameter & Result Contracts)

- **169 个基金指标 100% 全量人工标注与校验**:
  - 全量指标目录位于 `docs/temp/metrics.json`，契约规范 Schema 位于 `docs/temp/metric-parameter-contract.schema.json`。
  - 只读覆盖率报告：`docs/temp/metric-parameter-contract-coverage.json`。
  - **结果契约 (Result Contract)** 规范：
    - `DECIMAL`: 严格配置 `precision` (`totalDigits`, `scale`, `roundingMode: HALF_UP`) 与可选 `displayScale`；非数值类型禁止出现 `precision`。
    - `unit`: 采用真实物理符号 (`"%"`, `"元"`, `"天"`, `"年"`, `"名"`, `"次"`, `"只"`, `"户"`) 或 `null`；杜绝英文代码 (`PERCENT`, `YUAN`)。
    - `supportedOperators`: 枚举/字符串仅允许 `["=", "!="]`，禁止大小运算符；数值型指标提供标准运算符集合。
    - `storageConvention`: 最大回撤指标配置为 `"NEGATIVE_PERCENT"`，下游编译器读取契约自动执行负数口径翻转，不再按指标名称硬编码。
    - 枚举映射：输入参数枚举 (`ratingTerm`, `creditRating`) 与结果枚举 (`f_info_risklevel`) 均维护整数值 `value`、展示标签 `label` 及别名集合 `aliases`。
- **纯标准库校验器 (Zero External Dependencies)**:
  - 位于 `scripts/data_sync/validate_metric_parameter_contracts.py`，不依赖第三方 `jsonschema` 库，支持严格层级校验。
- **PostgreSQL 契约元数据与数据同步**:
  - 数据库迁移脚本：`docker/postgres/migrations/20260929_add_metric_parameter_contracts.sql`，在 `rag_fund_metric` 表中增加 `parameters JSONB NOT NULL DEFAULT '[]'` 与 `result_contract JSONB NOT NULL DEFAULT '{}'` 字段。
  - 同步运维命令 (`scripts/data_sync/sync_metric_contracts.py`)：
    ```powershell
    # 1. 模拟试跑 (dry-run)
    python scripts/data_sync/sync_metric_contracts.py --dry-run
    
    # 2. 正式同步 (apply - 零向量扰动，仅更新契约字段)
    python scripts/data_sync/sync_metric_contracts.py --apply
    
    # 3. 校验数据库一致性 (verify-only)
    python scripts/data_sync/sync_metric_contracts.py --verify-only
    ```
- **生成只读覆盖率报告**:
  ```powershell
  python scripts/data_sync/generate_coverage_report.py
  ```
- **确定性函数序列化与执行**:
  - `serialize_metric_call(metric_name, contract, values)`：严格按契约中的 `position` 顺序编译为 `f_name(windCode, arg2, ...)` 形式。
  - 自动补全规则：
    - 报告期缺省自动补齐 `reportDate="LATEST_REPORT_DATE"`；
    - 截止日期缺省自动补齐 `endDate="LATEST_TRADE_DATE"`；
    - 风险基准缺省自动补齐 `indexCode="FUND_BENCHMARK"`；
    - 区间校验强制保证 `startDate <= endDate`；
    - 缺少必填参数或传入非法枚举选项时，安全触发澄清请求 (`Clarification`)，绝不生成损坏的函数调用。

### 4. 运行自动化测试套件

```powershell
# 运行数据同步与契约校验测试套件 (31 tests)
python -m unittest discover -s scripts/data_sync/tests -p "test_*.py"

# 运行基金筛选服务全量测试套件 (105 passed, 5 skipped)
pytest

# 运行业务场景集成测试 (11 business scenarios)
pytest -v tests/integration/test_business_scenarios.py
pytest -v tests/integration/test_metric_parameter_completion.py
pytest -v tests/unit/test_metric_function_serializer.py
```


### 4. 运行真实依赖 Smoke Test 与 Agy Gate

在具有本地真实 LLM（如 Agy Bridge 运行于 http://127.0.0.1:8000/v1）、PgVector、Redis 环境下：

```powershell
# 1. 检查服务就绪状态 (Readiness Probe)
curl http://127.0.0.1:8810/ready

# 2. 运行真实图引擎端到端 Smoke 测试
$env:RUN_REAL_FUND_SCREENING_TESTS = "1"
pytest -v tests/integration/test_real_graph_smoke.py

# 3. 运行集成参数补全测试 (使用真实 Catalog fixture)
pytest -v tests/integration/test_metric_parameter_completion.py
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

