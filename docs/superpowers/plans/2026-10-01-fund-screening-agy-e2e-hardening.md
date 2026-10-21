# Fund Screening Agy E2E Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将已经接通 Agy 的基金筛选服务从“结构性 smoke 可通过”提升到“自然语言语义、RAG 候选、DSL 数值和多轮编辑均可精确验收”，并恢复完整测试全绿。

**Architecture:** 保留现有五段编译链路和 SemanticPlan → condition recall → adjudication 三级语义架构。先统一新旧模型与可观测数据，再用机械语义覆盖检查约束 LLM 输出，最后建立真实 Agy 黄金评测和延迟基线；LLM 负责语义拆解与候选裁决，日期解析、契约校验、单位换算、路由和修复保持确定性。

**Tech Stack:** Python 3.13、FastAPI、WebSocket、LangChain、LangGraph、Pydantic v2、PostgreSQL/pgvector、Redis、Ollama-compatible Embedding、Agy CLI OpenAI-compatible bridge、pytest。

**Spec:** `docs/superpowers/specs/2026-09-29-fund-screening-semantic-routing-design.md`

## Global Constraints

- 意图只对外暴露 `CREATE`、`EDIT`；CREATE 分 `direct/simple/full`，EDIT 分 `simple/complex`。
- 每个 BusinessGroup 必须有 sector；未指定时使用默认基金板块。
- metric 逻辑节点只能包含 `operator/children`，`params` 只允许出现在具体指标叶子。
- 根节点和每个 BusinessGroup 都拥有独立 `sort/limit`；“改取前 N 只”默认只修改根节点。
- 动态日期必须在 Finalize 前解析成 `yyyyMMdd`，最终 DSL 不允许 `LATEST_TRADE_DATE` 等占位符。
- 指标计算值来自数据引擎，LLM 不计算收益、回撤、Sharpe、Calmar 等金融数值。
- 不删除或重编号指标、板块；alias 仅作为召回补充，最终必须绑定既有 canonical id。
- Agy 默认模型固定为 `gemini-3.8-flash-low`，Bridge 默认串行，优化前先保留稳定性基线。

## Verified Baseline — 2026-10-01

- Bridge 单测：`8 passed`。
- Agy `/health`：`{"status":"ok","backend":"agy-cli"}`。
- Agy JSON Schema 最小真实调用：成功，约 19 秒。
- 真实依赖测试：`10 passed, 1 failed`，失败来自 catalog 测试夹具未注入，不是 LLM 请求失败。
- 全量离线测试：`238 passed, 20 failed, 6 skipped`。
- 新增组件专项测试：`22 passed`；数据同步测试：`31 passed`。
- 真实业务反例：`规模大于10亿且成立超过5年` 返回 DSL 仅保留成立年限、值为 `0`，但原 smoke 仍通过。
- 当前 trace 错读旧字段，真实 SemanticPlan 路径显示 `condition_count=0/candidate_count=0`，不可用于诊断。

---

### Task 1: 固化真实业务反例，消除 smoke 假绿

**Files:**
- Modify: `fund-screening-service/tests/integration/test_real_graph_smoke.py`
- Create: `fund-screening-service/tests/acceptance/test_business_golden_dsl.py`
- Create: `fund-screening-service/tests/fixtures/business-golden-cases.json`

**Interfaces:**
- Consumes: `LangGraphCommandCompiler.compile(query, previous_dsl=None, conversation_id=None)`。
- Produces: `assert_business_dsl(case, result)`，精确检查指标、值、逻辑、sector、sort、limit 和参数。

- [ ] **Step 1: 写离线黄金断言，覆盖当前假绿用例**

```python
def test_scale_and_existing_year_are_both_preserved(compiled_result):
    leaves = metric_leaves(compiled_result.dsl)
    assert [(x.metric, x.operator, x.value, x.unit) for x in leaves] == [
        ("f_netasset_total", ">", 1_000_000_000, "元"),
        ("f_info_existingyear", ">", 5, "年"),
    ]
```

- [ ] **Step 2: 将真实 smoke 的浅断言替换为业务断言**

```python
assert result.trace["route"] == "simple"
assert result.trace["condition_count"] == 2
assert {leaf.metric for leaf in metric_leaves(result.dsl)} == {
    "f_netasset_total", "f_info_existingyear"
}
```

- [ ] **Step 3: 增加不少于 8 个黄金场景**

```json
[
  {"query":"规模大于10亿且成立超过5年","metrics":["f_netasset_total","f_info_existingyear"]},
  {"query":"最大回撤低于15%","metrics":["f_risk_maxdownside"]},
  {"query":"季度收益大于10%","metrics":["f_nav_adjustedreturn"]},
  {"query":"主动权益基金按近3年收益降序取前20只","limit":20},
  {"query":"易方达管理的基金","entityRole":"FUND_COMPANY"},
  {"query":"张坤管理的基金","entityRole":"FUND_MANAGER"},
  {"query":"重仓沪电股份的基金","entityRole":"HOLDING_STOCK"},
  {"query":"跟踪沪深300的基金","entityRole":"TRACKED_INDEX"}
]
```

- [ ] **Step 4: 运行失败基线**

Run: `python -m pytest -q tests/acceptance/test_business_golden_dsl.py`

Expected: 当前至少在“规模+成立年限”场景失败，明确显示缺失指标和值错误。

- [ ] **Step 5: 提交验收测试**

```powershell
git add fund-screening-service/tests/acceptance fund-screening-service/tests/fixtures/business-golden-cases.json fund-screening-service/tests/integration/test_real_graph_smoke.py
git commit -m "test(screening): enforce exact business DSL semantics"
```

### Task 2: 统一 SemanticPlan 主链路并修复可观测 trace

**Files:**
- Modify: `fund-screening-service/app/graph/compiler.py`
- Modify: `fund-screening-service/app/graph/state.py`
- Modify: `fund-screening-service/app/graph/nodes.py`
- Test: `fund-screening-service/tests/unit/test_graph_semantic_pipeline.py`
- Test: `fund-screening-service/tests/unit/test_graph_nodes.py`

**Interfaces:**
- Consumes: `semantic_plan`、`by_condition`、`candidate_contract_index`、`adjudication_result`。
- Produces: 新 trace 字段 `group_count/condition_count/recalled_condition_count/candidate_count/binding_count/route_reason/llm_calls`。

- [ ] **Step 1: 写 trace 失败测试**

```python
assert result.trace == {
    "route": "simple",
    "route_reason": "SINGLE_GROUP_SHALLOW_AND",
    "group_count": 1,
    "condition_count": 2,
    "recalled_condition_count": 2,
    "candidate_count": 6,
    "binding_count": 2,
    "llm_calls": 2,
    "repairs": [],
}
```

- [ ] **Step 2: 从新状态字段计算 trace，不再读取旧 `recall_plan.conditions`**

```python
plan = SemanticPlan.model_validate(result["semantic_plan"])
by_condition = result.get("by_condition") or {}
bindings = (result.get("adjudication_result") or {}).get("conditionBindings", [])
```

- [ ] **Step 3: 在 state 中保存 `route_reason` 与 `llm_calls`**

`simple_full_router` 必须同时写入 route 和 reason；每个生成式节点完成后递增 `llm_calls`，Direct 和 Simple Edit 保持为 0。

- [ ] **Step 4: 初始化所有分支局部变量**

在 `command_build` 入口初始化 `resolutions = []`，确保旧兼容路径不会触发 `UnboundLocalError`。

- [ ] **Step 5: 运行 Graph 定向测试**

Run: `python -m pytest -q tests/unit/test_graph_semantic_pipeline.py tests/unit/test_graph_nodes.py tests/unit/test_graph_routing.py`

Expected: PASS。

### Task 3: 拆分三类 Condition Binding 契约

**Files:**
- Modify: `fund-screening-service/app/models/semantic_plan.py`
- Modify: `fund-screening-service/app/retrieval/llm.py`
- Modify: `fund-screening-service/app/pipeline/builder.py`
- Test: `fund-screening-service/tests/unit/test_semantic_validator.py`
- Test: `fund-screening-service/tests/integration/test_semantic_routing_acceptance.py`

**Interfaces:**
- Produces: `MetricConditionBinding | ScopeConditionBinding | EntityConditionBinding` 判别联合。

- [ ] **Step 1: 写三类模型测试**

```python
MetricConditionBinding(conditionId="m1", domain="metric", candidateKey="f_info_existingyear", operator=">", value=5)
ScopeConditionBinding(conditionId="s1", domain="scope", candidateKey="1000019220000000")
EntityConditionBinding(conditionId="e1", domain="entity", candidateKey="manager:张坤", bindingRole="FUND_MANAGER")
```

要求 metric 缺 value 时失败，而 scope/entity 不因缺比较值失败。

- [ ] **Step 2: 使用 `Field(discriminator="domain")` 定义联合**

```python
ConditionBinding = Annotated[
    MetricConditionBinding | ScopeConditionBinding | EntityConditionBinding,
    Field(discriminator="domain"),
]
```

- [ ] **Step 3: 更新 LLM schema 与 builder 分派**

Metric 从叶子取得 operator/value/params；Scope 只构造 sectorId；Entity 根据 candidate contract 的 preferredIndicator/bindingRole/valueType 构造指标叶子。

- [ ] **Step 4: 运行实体和多组测试**

Run: `python -m pytest -q tests/unit/test_semantic_validator.py tests/integration/test_semantic_routing_acceptance.py`

Expected: PASS。

### Task 4: 完成 DSL 节点迁移并收敛 CREATE/EDIT 词汇

**Files:**
- Modify: `fund-screening-service/app/models/dsl.py`
- Modify: `fund-screening-service/app/models/intent.py`
- Modify: `fund-screening-service/app/models/semantic_plan.py`
- Modify: `fund-screening-service/app/pipeline/validator.py`
- Modify: `fund-screening-service/app/graph/workflow.py`
- Modify: `fund-screening-service/tests/**/*.py`

**Interfaces:**
- Produces: 生产代码只使用 `MetricPredicate`、`MetricLogicNode`；意图只使用 `CREATE|EDIT`。

- [ ] **Step 1: 将所有 `MetricExpression(...)` 测试构造迁移到新节点**

Run: `rg -n "MetricExpression\(" fund-screening-service`

Expected after migration: 0 matches。

- [ ] **Step 2: 删除伪 Pydantic 兼容构造器**

保留 `TypeAdapter(MetricNode)` 作为解析入口，不再让 `MetricExpression` 同时承担类型别名和类。

- [ ] **Step 3: 删除 REBUILD 分支和兼容 Literal**

Run: `rg -n "REBUILD" fund-screening-service/app fund-screening-service/tests`

Expected after migration: 0 matches。

- [ ] **Step 4: 执行完整离线测试**

Run: `python -m pytest -q`

Expected: 除显式真实依赖 skip 外全部 PASS。

### Task 5: 增加语义覆盖闸门，防止 LLM 漏条件和填零

**Files:**
- Create: `fund-screening-service/app/pipeline/semantic_coverage.py`
- Modify: `fund-screening-service/app/graph/nodes.py`
- Modify: `fund-screening-service/app/retrieval/llm.py`
- Test: `fund-screening-service/tests/unit/test_semantic_coverage.py`

**Interfaces:**
- Produces: `check_semantic_coverage(query: str, plan: SemanticPlan) -> CoverageResult`。
- `CoverageResult` 包含 `expected_comparisons`、`covered_mentions`、`missing_mentions`、`invalid_zero_values`。

- [ ] **Step 1: 写覆盖检测失败测试**

```python
result = check_semantic_coverage(
    "规模大于10亿且成立超过5年",
    plan_with_only_existing_year_zero,
)
assert result.missing_mentions == ["规模大于10亿"]
assert result.invalid_zero_values == ["成立超过5年"]
```

- [ ] **Step 2: 只机械提取显式比较片段，不猜指标 ID**

正则识别 `大于/超过/不低于/小于/低于/不超过/=/>=/<=` 与其紧邻数值和单位；通过 plan leaf 的 source mention/value 做覆盖比对。

- [ ] **Step 3: SemanticPlan 不完整时执行一次有界重试**

重试 prompt 必须附带缺失片段和错误值；最多重试 1 次。第二次仍不完整时返回 clarification，不允许带着残缺 DSL 继续。

- [ ] **Step 4: Adjudication 后再次校验绑定覆盖**

每个 MetricLeaf 必须恰好有一个 binding；binding value 必须来自 SemanticPlan 的明确值或参数补全器，不能使用通用 `0` 占位。

- [ ] **Step 5: 运行覆盖与黄金测试**

Run: `python -m pytest -q tests/unit/test_semantic_coverage.py tests/acceptance/test_business_golden_dsl.py`

Expected: PASS。

### Task 6: 修复 EDIT 作用域和根/组排序截断语义

**Files:**
- Modify: `fund-screening-service/app/pipeline/editor.py`
- Modify: `fund-screening-service/app/models/edit_plan.py`
- Test: `fund-screening-service/tests/unit/test_editor.py`
- Test: `fund-screening-service/tests/api/test_commands.py`
- Test: `fund-screening-service/tests/api/test_websocket.py`

**Interfaces:**
- Produces: `EditTargetScope = ROOT | GROUP | ALL_GROUPS`，默认 ROOT。

- [ ] **Step 1: 写根 limit 不改变 children 的测试**

```python
before_children = previous.model_dump()["children"]
edited = apply_simple_edit(previous, "改取前50只")
assert edited.limit == 50
assert edited.model_dump()["children"] == before_children
```

- [ ] **Step 2: 写显式每组编辑测试**

```python
edited = apply_simple_edit(previous, "每组取前50只")
assert edited.limit == previous.limit
assert all(group.limit == 50 for group in edited.children)
```

- [ ] **Step 3: sort 使用相同作用域规则**

“改成升序”只更新根 sort；“每组按规模降序”更新组 sort。

- [ ] **Step 4: 运行 HTTP/WebSocket/Editor 测试**

Run: `python -m pytest -q tests/unit/test_editor.py tests/api/test_commands.py tests/api/test_websocket.py`

Expected: PASS，HTTP 与 WebSocket 输出一致。

### Task 7: 固化 Agy Bridge 配置、健康检查和真实测试夹具

**Files:**
- Modify: `codex-bridge/README.md`
- Modify: `fund-screening-service/app/config.py`
- Modify: `fund-screening-service/app/main.py`
- Create: `fund-screening-service/tests/integration/conftest.py`
- Modify: `fund-screening-service/tests/integration/test_metric_parameter_completion.py`
- Test: `fund-screening-service/tests/unit/test_runtime.py`

**Interfaces:**
- Produces: session-scoped `real_settings` fixture，统一注入 metrics/sectors/entities、Agy、Embedding、DB、Redis 配置。

- [ ] **Step 1: 将重复的 catalog path 搜索收敛到 fixture**

```python
@pytest.fixture(scope="session")
def real_settings(repo_root: Path) -> Settings:
    return Settings(
        metrics_catalog_path=str(repo_root / "docs/temp/metrics.json"),
        sectors_catalog_path=str(repo_root / "docs/temp/sectors.json"),
        entity_catalog_path=str(SERVICE_ROOT / "tests/fixtures/entities-small.json"),
    )
```

- [ ] **Step 2: 修复当前唯一真实测试配置失败**

`test_real_llm_parameter_completion_smoke` 使用 `real_settings`，不再直接调用空路径的 `Settings()`。

- [ ] **Step 3: `/health` 区分进程健康与依赖健康**

返回 `llm/embedding/postgres/redis` 四项状态；`/ready` 仅在必需依赖全部可用时返回 200。

- [ ] **Step 4: 增加 Bridge 启动和真实测试命令**

```powershell
$env:CODEX_BRIDGE_BACKEND = "agy"
$env:CODEX_BRIDGE_AGY_COMMAND = "C:\Users\longlong\AppData\Local\agy\bin\agy.exe"
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000

$env:RUN_REAL_FUND_SCREENING_TESTS = "1"
python -m pytest -q tests/integration/test_real_graph_smoke.py tests/integration/test_metric_parameter_completion.py
```

- [ ] **Step 5: 运行 Bridge 和真实依赖测试**

Expected: Bridge `8 passed`；真实依赖 `11 passed`。

### Task 8: 建立 RAG 质量、真实 LLM 稳定性与性能门禁

**Files:**
- Modify: `fund-screening-service/app/evaluation/runner.py`
- Modify: `fund-screening-service/app/evaluation/report.py`
- Create: `fund-screening-service/tests/evaluation/test_real_agy_golden.py`
- Modify: `fund-screening-service/README.md`

**Interfaces:**
- Produces: 每案例 `plan_coverage/recall_top1/recall_top3/binding_accuracy/dsl_accuracy/repair_count/latency_ms/llm_calls`。

- [ ] **Step 1: 给报告增加分阶段指标**

```python
assert report.summary.keys() >= {
    "plan_coverage", "recall_top1", "recall_top3",
    "binding_accuracy", "dsl_accuracy", "p50_latency_ms", "p95_latency_ms"
}
```

- [ ] **Step 2: 建立 Agy 重复运行稳定性测试**

对核心 8 个黄金案例各运行 3 次；比较 canonical DSL，不比较 conditionId 等非业务字段。

- [ ] **Step 3: 设置 MVP 门禁**

```text
Semantic Plan 条件覆盖率 = 100%
DSL 指标/运算符/值准确率 = 100%（核心 8 案例）
Recall Top-3 >= 95%
Direct LLM calls = 0
Simple/Full LLM calls <= 2；仅覆盖重试时允许 3
最终 DSL 动态占位符数量 = 0
真实案例崩溃率 = 0
```

- [ ] **Step 4: 记录性能基线而非立即并发化 Agy**

先记录 plan、recall、adjudication、build、validate 各阶段耗时。只有确认 CLI 启动占比后，才评估 stream-json 长驻会话或小型连接池；不得在同一会话复用业务上下文。

- [ ] **Step 5: 最终验收**

```powershell
python -m pytest -q
$env:RUN_REAL_FUND_SCREENING_TESTS = "1"
python -m pytest -q tests/integration tests/evaluation/test_real_agy_golden.py
python -m unittest discover -s scripts/data_sync/tests -p "test_*.py"
git diff --check
```

Expected:

```text
离线测试：0 failed，真实依赖项之外允许明确 skip
真实 Agy：0 failed
数据同步：0 failed
git diff --check：无 trailing whitespace/new blank line 错误
```

## Implementation Order and Review Gates

1. Task 1–2：先让错误可见，禁止根据旧 smoke 宣称完成。
2. Task 3–4：收敛模型和词汇，恢复离线测试全绿。
3. Task 5–6：修复真实业务正确性和多轮编辑语义。
4. Task 7：恢复真实依赖测试全绿并固化启动方式。
5. Task 8：建立长期 RAG/LLM 质量与延迟门禁。

每个 Gate 都必须先运行任务内定向测试，再运行完整离线测试；不得把新失败标记为 skip 来换取绿灯。

## Self-Review Result

- Spec coverage：覆盖五段编译、三层语义、三路召回、CREATE/EDIT 分流、实体契约、参数补全、校验修复、多轮编辑和 RAG 评测。
- Placeholder scan：无 TBD/TODO/“后续补充”步骤。
- Type consistency：统一使用 SemanticPlan、三类 ConditionBinding、MetricPredicate/MetricLogicNode 和 FundScreeningDsl。
- Scope control：Agy 性能优化先测量再决定是否长驻，不在正确性修复中引入并发架构变化。
