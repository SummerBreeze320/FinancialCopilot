# Fund Screening Compiler Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将当前基金筛选服务收敛为仅含 CREATE/EDIT 两类意图、支持递归语义树和按 Condition 召回、能够输出参数完整且可直接执行的双层排序/截断 DSL，并建立可维护的 alias、Embedding、时间区间指标和候选优先级机制。

**Architecture:** 请求先经过三层意图漏斗，未明确编辑上一轮时默认 CREATE；CREATE 分为 Direct/Simple/Full，EDIT 分为 Simple/Complex。非 Direct 请求由 LLM 生成唯一逻辑来源 SemanticPlan，RAG 按 conditionId 召回真实目录候选，第二次 LLM 只绑定叶子；CommandBuilder 机械构建 DSL，Validator 解析动态日期、单位、默认排序和参数后才允许 Finalize。

**Tech Stack:** Python 3.11、FastAPI、Pydantic 2、LangChain、LangGraph、PostgreSQL/pgvector、Qwen3 Embedding、Redis、pytest。

**Spec:** `docs/superpowers/specs/2026-09-29-fund-screening-semantic-routing-design.md`

## Global Constraints

- 一级意图只允许 `CREATE` 与 `EDIT`；不再把 CANCEL、OUT_OF_SCOPE、CLARIFY 或 REBUILD 作为一级意图。
- `previousDsl` 的存在不是 EDIT 证据；没有明确修改、延续或引用上一轮时默认 CREATE。
- CREATE 分为 `DIRECT`、`SIMPLE`、`FULL`；EDIT 只分为 `SIMPLE`、`COMPLEX`，不向下暴露更多路由类型。
- Direct 必须 0 次生成式 LLM；Simple/Full 使用 SemanticPlan LLM 与叶子裁决 LLM。
- SemanticPlan 是 Group、Scope 和 Metric 逻辑树的唯一来源；裁决 LLM 不得重建逻辑树。
- 最终 DSL 只能引用目录中真实存在的 metric、sector、entity，不新增、删除或合并虚拟目录对象。
- 多个歧义候选必须选择一个最合适的真实对象；选择依据必须确定、可配置、可追踪。
- 每个 Group 和根节点都必须拥有自己的 `sort` 与 `limit`。
- Sector 叶子只输出 `sectorId`，不得输出 `match`。
- 逻辑节点不得输出 `metric`、`value`、`unit`、`params`；这些字段仅属于指标叶子。
- 最终 DSL 不得包含 `LATEST_TRADE_DATE`、`LATEST_REPORT_DATE` 等内部动态占位符。
- 单位使用业务符号或中文单位，如 `%`、`元`、`年`，不得把 `DECIMAL`、`INTEGER` 当作单位。
- 目录中的 alias 可以补充，但不得为解决召回问题新增或删除指标/板块。
- 金融指标计算和日期解析使用确定性工具/服务，不让 LLM自行计算收益、日期或单位换算。
- 当前工作树含未提交改动；实现时不得重置或覆盖无关修改。仓库规则禁止未经授权提交，因此本计划不包含自动 commit 步骤，每个任务结束改为提交前差异检查。

---

## File Structure

### Create

- `fund-screening-service/app/models/dsl_nodes.py`：区分指标逻辑节点与指标叶子的递归 DSL 类型。
- `fund-screening-service/app/pipeline/dynamic_defaults.py`：将动态日期默认值解析为具体 `yyyyMMdd`。
- `fund-screening-service/app/pipeline/metric_resolution.py`：固定周期指标优先、通用区间指标兜底的确定性解析策略。
- `fund-screening-service/app/retrieval/candidate_ranker.py`：精确名称、alias、业务限定和默认优先级的候选排序。
- `fund-screening-service/tests/unit/test_dsl_leaf_contract.py`：逻辑节点/叶子字段边界测试。
- `fund-screening-service/tests/unit/test_dynamic_defaults.py`：动态日期解析测试。
- `fund-screening-service/tests/unit/test_metric_resolution.py`：季度/滚动时间指标解析测试。
- `fund-screening-service/tests/unit/test_candidate_ranker.py`：指标和板块歧义选择测试。
- `fund-screening-service/tests/integration/test_executable_dsl_contract.py`：完整可执行 DSL 验收测试。

### Modify

- `fund-screening-service/app/models/intent.py`：将 `REBUILD` 重命名为 `EDIT`，保持一级意图只有 CREATE/EDIT。
- `fund-screening-service/app/pipeline/intent_funnel.py`：三层漏斗默认 CREATE，明确编辑才进入 EDIT。
- `fund-screening-service/app/models/semantic_plan.py`：增加时间语义、Group/Root排序语义，并把叶子绑定值改为必填。
- `fund-screening-service/app/models/dsl.py`：组合新的递归节点类型，删除 SectorLeaf.match，要求双层 sort/limit。
- `fund-screening-service/app/retrieval/llm.py`：收敛意图Prompt；向裁决模型传递单位、参数和候选来源；裁决只输出叶子绑定。
- `fund-screening-service/app/pipeline/analysis.py`：并行Condition召回，并在写入byCondition前调用候选排序器。
- `fund-screening-service/app/pipeline/builder.py`：由 PlanTree + Bindings 构建无冗余字段DSL并补双层默认sort/limit。
- `fund-screening-service/app/pipeline/validator.py`：校验必填value、单位、动态日期、双层排序和limit。
- `fund-screening-service/app/graph/direct_probe.py`：Direct输出遵守新DSL契约。
- `fund-screening-service/app/graph/router.py`：CREATE分Direct/Simple/Full；EDIT分Simple/Complex。
- `fund-screening-service/app/graph/state.py`：统一CREATE/EDIT和时间解析上下文状态。
- `fund-screening-service/app/graph/nodes.py`：接入日期解析、指标回退、候选排序及新的Builder/Validator。
- `fund-screening-service/app/graph/workflow.py`：按CREATE/EDIT两类意图重接条件边。
- `scripts/data_sync/sync_fund_screening_rag.py`：生成Embedding文本时保留人工embedding_text并合并alias，移除通用噪声字段。
- `docs/temp/metrics.json`：只为现有指标补充经过审核的alias和默认优先级。
- `docs/temp/sectors.json`：只为现有板块补充alias和默认优先级。
- `fund-screening-service/app/evaluation/*`：评估精确候选选择、最终DSL和参数，不把“相关指标”算作正确。

---

### Task 1: 固化可执行DSL的失败验收用例

**Files:**
- Create: `fund-screening-service/tests/integration/test_executable_dsl_contract.py`
- Modify: `fund-screening-service/tests/unit/test_ast_command_builder.py`

**Interfaces:**
- Consumes: 当前 `FundScreeningDsl`、`CommandBuilder.build_from_plan(...)`、`validate_and_repair(...)`。
- Produces: 后续任务共同遵守的端到端DSL断言。

- [ ] **Step 1: 写入当前案例的失败测试**

测试固定输入：

```python
QUERY = "规模大于10亿且成立超过5年"
AS_OF_DATE = "20260930"
```

断言最终DSL满足：

```python
assert dsl.children[0].metric.model_dump().get("params") is None
assert dsl.children[0].metric.children[0].value == 1_000_000_000
assert dsl.children[0].metric.children[0].unit == "元"
assert dsl.children[0].metric.children[1].value == 5
assert dsl.children[0].metric.children[1].unit == "年"
assert all(p["value"] == "20260930" for leaf in dsl.children[0].metric.children for p in leaf.params)
assert dsl.children[0].sector.model_dump(by_alias=True)["children"] == [
    {"sectorId": "1000019220000000"}
]
assert dsl.children[0].sort
assert dsl.children[0].limit == 20
assert dsl.sort
assert dsl.limit == 20
assert "LATEST_" not in json.dumps(dsl.model_dump(by_alias=True), ensure_ascii=False)
```

- [ ] **Step 2: 运行测试确认在当前实现上失败**

Run:

```powershell
cd fund-screening-service
pytest -q tests/integration/test_executable_dsl_contract.py
```

Expected: FAIL，至少显示逻辑节点存在 `params`、叶子缺少 `value`、单位为 `DECIMAL`、动态日期未解析或默认排序为空。

- [ ] **Step 3: 增加单项回归断言**

在 `test_ast_command_builder.py` 增加以下独立用例：

```text
test_logic_node_never_serializes_leaf_fields
test_metric_leaf_requires_value
test_sector_leaf_only_serializes_sector_id
test_group_and_root_have_stable_default_sort_and_limit
```

- [ ] **Step 4: 记录基线失败而不修改生产代码**

Run:

```powershell
git diff -- fund-screening-service/tests/integration/test_executable_dsl_contract.py fund-screening-service/tests/unit/test_ast_command_builder.py
```

Expected: 只包含新验收测试，不含生产代码变更。

---

### Task 2: 拆分逻辑节点与指标叶子并删除Sector match

**Files:**
- Create: `fund-screening-service/app/models/dsl_nodes.py`
- Modify: `fund-screening-service/app/models/dsl.py`
- Modify: `fund-screening-service/app/pipeline/builder.py`
- Modify: `fund-screening-service/app/graph/direct_probe.py`
- Test: `fund-screening-service/tests/unit/test_dsl_leaf_contract.py`
- Test: `fund-screening-service/tests/unit/test_dsl_schema.py`

**Interfaces:**
- Produces: `MetricPredicate`, `MetricLogicNode`, `MetricNode`、不含match的`SectorLeaf`。
- Consumes: Pydantic 2递归模型；后续Builder和Validator只依赖新类型。

- [ ] **Step 1: 写失败模型测试**

```python
def test_logic_node_has_no_leaf_fields():
    node = MetricLogicNode(
        operator="AND",
        children=[MetricPredicate(metric="f_scale", operator=">", value=10, unit="元")],
    )
    assert node.model_dump(exclude_none=True) == {
        "operator": "AND",
        "children": [{"metric": "f_scale", "operator": ">", "value": 10, "unit": "元", "params": []}],
    }


def test_metric_predicate_rejects_missing_value():
    with pytest.raises(ValidationError):
        MetricPredicate(metric="f_scale", operator=">")


def test_sector_leaf_only_contains_sector_id():
    leaf = SectorLeaf(sectorId="1000019220000000")
    assert leaf.model_dump(by_alias=True) == {"sectorId": "1000019220000000"}
```

- [ ] **Step 2: 运行模型测试确认失败**

Run: `pytest -q tests/unit/test_dsl_leaf_contract.py`

Expected: FAIL，因为新类型不存在且旧逻辑节点仍暴露params。

- [ ] **Step 3: 实现判别联合节点**

在 `dsl_nodes.py` 定义：

```python
class MetricPredicate(BaseModel):
    metric: str
    operator: Literal[">", ">=", "<", "<=", "=", "!="]
    value: Any
    unit: str | None = None
    period: dict[str, Any] | None = None
    params: list[dict[str, Any]] = Field(default_factory=list)


class MetricLogicNode(BaseModel):
    operator: Literal["AND", "OR", "NOT"]
    children: list[MetricNode]


MetricNode = MetricPredicate | MetricLogicNode
```

为 `NOT` 增加单子节点校验。`dsl.py` 的 `BusinessGroup.metric` 改为 `MetricNode`；`SectorLeaf`删除match字段。

- [ ] **Step 4: 更新Builder和Direct Probe使用新节点**

逻辑树分支只构造 `MetricLogicNode`；condition绑定只构造 `MetricPredicate`。删除所有逻辑节点的 `params=[]`、`unit`和`value`传参，删除所有SectorLeaf的match。

- [ ] **Step 5: 运行DSL和Builder测试**

Run:

```powershell
pytest -q tests/unit/test_dsl_leaf_contract.py tests/unit/test_dsl_schema.py tests/unit/test_ast_command_builder.py tests/unit/test_tree_preserving_builder.py
```

Expected: PASS。

- [ ] **Step 6: 检查差异范围**

Run: `git diff -- fund-screening-service/app/models fund-screening-service/app/pipeline/builder.py fund-screening-service/app/graph/direct_probe.py`

Expected: 只包含DSL类型边界和对应构造调用变更。

---

### Task 3: 收紧叶子裁决契约并完整传递指标元数据

**Files:**
- Modify: `fund-screening-service/app/models/semantic_plan.py`
- Modify: `fund-screening-service/app/retrieval/llm.py`
- Modify: `fund-screening-service/app/pipeline/builder.py`
- Test: `fund-screening-service/tests/unit/test_candidate_adjudication.py`
- Test: `fund-screening-service/tests/unit/test_resolved_condition_models.py`

**Interfaces:**
- Produces: 必填 `ResolvedCondition.value`；紧凑但完整的候选裁决视图。
- Consumes: `CandidateContract.result_contract`、`parameters`、`supported_operators`。

- [ ] **Step 1: 写失败测试证明value不能缺省**

```python
def test_resolved_metric_condition_requires_value():
    with pytest.raises(ValidationError):
        ResolvedCondition(
            conditionId="m1",
            selectedCandidateId="f_netasset_total",
            operator=">",
        )
```

- [ ] **Step 2: 写候选Prompt序列化测试**

给定：

```python
CandidateContract(
    domain="metric",
    key="f_netasset_total",
    name="基金规模合计",
    value_type="DECIMAL",
    result_contract={"unit": "元", "acceptedInputUnits": [{"unit": "亿元", "multiplier": 100000000}]},
)
```

断言传给裁决Runnable的JSON同时包含：

```text
valueType=DECIMAL
unit=元
acceptedInputUnits
supportedOperators
parameters
```

- [ ] **Step 3: 运行测试确认失败**

Run: `pytest -q tests/unit/test_candidate_adjudication.py tests/unit/test_resolved_condition_models.py`

Expected: FAIL，因为value当前默认None，候选摘要当前遗漏resultContract。

- [ ] **Step 4: 修改结构化输出Schema和Prompt**

删除 `ResolvedCondition.value` 默认值；Scope绑定使用独立类型或显式可空分支，避免Scope叶子被迫伪造value。裁决Prompt明确：

```text
指标叶子必须输出operator/value；unit必须来自resultContract.unit或用户可转换输入单位；禁止把valueType作为unit。
```

- [ ] **Step 5: 修改Builder的单位来源**

Builder不得使用：

```python
unit = candidate.value_type
```

只接受裁决绑定的业务单位，或回退到：

```python
candidate.result_contract.get("unit")
```

如果两者都不存在，保留 `None`，由Validator按结果契约决定是否接受。

- [ ] **Step 6: 运行裁决与Builder测试**

Run:

```powershell
pytest -q tests/unit/test_candidate_adjudication.py tests/unit/test_resolved_condition_models.py tests/unit/test_ast_command_builder.py
```

Expected: PASS。

---

### Task 4: 实现双层sort/limit和稳定默认排序

**Files:**
- Modify: `fund-screening-service/app/models/dsl.py`
- Modify: `fund-screening-service/app/models/semantic_plan.py`
- Modify: `fund-screening-service/app/pipeline/builder.py`
- Modify: `fund-screening-service/app/pipeline/validator.py`
- Test: `fund-screening-service/tests/unit/test_validator.py`
- Test: `fund-screening-service/tests/unit/test_ast_command_builder.py`

**Interfaces:**
- Produces: 每个Group和根节点均有必填sort/limit。
- Consumes: `SortBinding`的ROOT/GROUP作用域。

- [ ] **Step 1: 写双层默认失败测试**

```python
def test_builder_adds_group_and_root_default_sort():
    dsl = build_without_explicit_sort()
    assert dsl.children[0].sort == [SortItem(metric="fund_code", direction="ASC", nulls="LAST")]
    assert dsl.children[0].limit == 20
    assert dsl.sort == [SortItem(metric="fund_code", direction="ASC", nulls="LAST")]
    assert dsl.limit == 20
```

- [ ] **Step 2: 运行测试确认失败**

Run: `pytest -q tests/unit/test_ast_command_builder.py -k default_sort`

Expected: FAIL，因为当前sort为空且Group limit可空。

- [ ] **Step 3: 扩展排序语义模型**

`SortMention`和`SortBinding`增加：

```python
level: Literal["ROOT", "GROUP"] = "ROOT"
group_id: str | None = None
```

未明确“每组”时排序和数量属于ROOT；每个Group仍获得默认sort/limit。

- [ ] **Step 4: 实现Builder默认规则**

统一常量：

```python
DEFAULT_SORT = SortItem(metric="fund_code", direction="ASC", nulls="LAST")
DEFAULT_LIMIT = 20
```

每个Group先应用显式Group排序，否则默认；根节点先应用显式Root排序，否则默认。

- [ ] **Step 5: Validator同时校验两级分页**

校验Group和Root的limit范围、sort非空及排序指标可用性。`fund_code`作为数据引擎内建稳定键显式加入允许列表，不伪装为目录指标。

- [ ] **Step 6: 运行相关测试**

Run:

```powershell
pytest -q tests/unit/test_validator.py tests/unit/test_ast_command_builder.py tests/unit/test_dsl_schema.py
```

Expected: PASS。

---

### Task 5: 动态日期解析和最终DSL占位符清理

**Files:**
- Create: `fund-screening-service/app/pipeline/dynamic_defaults.py`
- Modify: `fund-screening-service/app/pipeline/validator.py`
- Modify: `fund-screening-service/app/graph/nodes.py`
- Modify: `fund-screening-service/app/runtime.py`
- Test: `fund-screening-service/tests/unit/test_dynamic_defaults.py`
- Test: `fund-screening-service/tests/unit/test_metric_parameter_validation.py`

**Interfaces:**
- Produces: `DynamicDefaultResolver.resolve(value: str, as_of_date: date) -> str`。
- Consumes: 交易日/报告期Port；测试使用固定Fake实现。

- [ ] **Step 1: 写固定日期的失败测试**

```python
class FakeBusinessCalendar:
    def latest_trade_date(self, as_of_date: date) -> date:
        return date(2026, 9, 30)

    def latest_report_date(self, as_of_date: date) -> date:
        return date(2026, 6, 30)


def test_dynamic_default_resolves_to_yyyymmdd():
    resolver = DynamicDefaultResolver(FakeBusinessCalendar())
    assert resolver.resolve("LATEST_TRADE_DATE", date(2026, 9, 30)) == "20260930"
    assert resolver.resolve("LATEST_REPORT_DATE", date(2026, 9, 30)) == "20260630"
```

- [ ] **Step 2: 运行测试确认失败**

Run: `pytest -q tests/unit/test_dynamic_defaults.py`

Expected: FAIL，因为Resolver不存在。

- [ ] **Step 3: 实现动态默认Resolver**

Resolver只处理白名单符号：

```text
LATEST_TRADE_DATE
LATEST_REPORT_DATE
```

未知 `LATEST_*` 返回结构化校验错误，不直接透传。

- [ ] **Step 4: 在Validator参数补齐后解析占位符**

执行顺序固定为：

```text
补默认参数 → 动态默认解析 → yyyyMMdd格式校验 → startDate/endDate顺序校验
```

- [ ] **Step 5: Finalize前增加全DSL占位符断言**

如果序列化DSL仍含 `LATEST_`，返回 `UNRESOLVED_DYNAMIC_DEFAULT` clarification，不允许交付。

- [ ] **Step 6: 运行参数和动态日期测试**

Run:

```powershell
pytest -q tests/unit/test_dynamic_defaults.py tests/unit/test_metric_parameter_validation.py tests/unit/test_validator.py
```

Expected: PASS。

---

### Task 6: 实现时间语义和区间收益指标回退

**Files:**
- Create: `fund-screening-service/app/pipeline/metric_resolution.py`
- Modify: `fund-screening-service/app/models/semantic_plan.py`
- Modify: `fund-screening-service/app/retrieval/llm.py`
- Modify: `fund-screening-service/app/graph/nodes.py`
- Test: `fund-screening-service/tests/unit/test_metric_resolution.py`
- Test: `fund-screening-service/tests/unit/test_semantic_plan_llm.py`

**Interfaces:**
- Produces: `TimeHint`、`MetricResolutionPolicy.resolve(...) -> MetricResolution`。
- Consumes: 真实目录中的固定周期指标和 `f_nav_adjustedreturn`。

- [ ] **Step 1: 写时间语义模型测试**

覆盖：

```text
本季度 → CALENDAR_QUARTER offset=0
上季度 → CALENDAR_QUARTER offset=-1
近三个月 → TRAILING 3 MONTH
今年以来 → YEAR_TO_DATE
2024年第二季度 → FIXED_QUARTER year=2024 quarter=2
```

- [ ] **Step 2: 写固定指标优先、区间指标兜底测试**

```python
def test_trailing_one_year_prefers_fixed_metric():
    result = resolver.resolve(TimeHint(type="TRAILING", value=1, unit="YEAR"), as_of="20260930")
    assert result.metric == "f_return_1y"
    assert result.params == {"endDate": "20260930"}


def test_current_quarter_falls_back_to_interval_return():
    result = resolver.resolve(TimeHint(type="CALENDAR_QUARTER", offset=0), as_of="20260930")
    assert result.metric == "f_nav_adjustedreturn"
    assert result.params == {"startDate": "20260701", "endDate": "20260930"}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `pytest -q tests/unit/test_metric_resolution.py tests/unit/test_semantic_plan_llm.py`

Expected: FAIL，因为TimeHint和Resolver不存在。

- [ ] **Step 4: 扩展SemanticPlan Prompt**

LLM只输出时间语义，不计算日期。明确区分自然季度与滚动三个月；“季度收益”缺少限定时按配置解释为当前自然季度。

- [ ] **Step 5: 实现确定性指标选择策略**

内置映射只引用现有指标：

```python
FIXED_RETURN_METRICS = {
    ("TRAILING", 1, "MONTH"): "f_return_1m",
    ("TRAILING", 3, "MONTH"): "f_return_3m",
    ("TRAILING", 6, "MONTH"): "f_return_6m",
    ("TRAILING", 1, "YEAR"): "f_return_1y",
    ("YEAR_TO_DATE", None, None): "f_return_ytd",
}
INTERVAL_RETURN_METRIC = "f_nav_adjustedreturn"
```

Resolver不得创建 `f_return_q` 等目录不存在的指标。

- [ ] **Step 6: 将解析结果绑定回Condition**

时间Resolver可以覆盖错误的通用收益候选，但覆盖目标必须是目录中存在的真实指标，并在Trace记录 `FIXED_WINDOW_SELECTION` 或 `INTERVAL_FALLBACK`。

- [ ] **Step 7: 运行时间解析测试**

Run:

```powershell
pytest -q tests/unit/test_metric_resolution.py tests/unit/test_semantic_plan_llm.py tests/integration/test_business_scenarios.py
```

Expected: PASS。

---

### Task 7: 收敛意图为CREATE/EDIT并实现对应分流

**Files:**
- Modify: `fund-screening-service/app/models/intent.py`
- Modify: `fund-screening-service/app/pipeline/intent_funnel.py`
- Modify: `fund-screening-service/app/pipeline/editor.py`
- Modify: `fund-screening-service/app/graph/state.py`
- Modify: `fund-screening-service/app/graph/router.py`
- Modify: `fund-screening-service/app/graph/workflow.py`
- Modify: `fund-screening-service/app/retrieval/llm.py`
- Test: `fund-screening-service/tests/unit/test_intent_funnel.py`
- Test: `fund-screening-service/tests/unit/test_semantic_router.py`
- Test: `fund-screening-service/tests/unit/test_multi_round_semantic_edit.py`

**Interfaces:**
- Produces: `IntentDecision(intent: Literal["CREATE", "EDIT"])`。
- Consumes: previousDsl、pending clarification、规则分类器和两级模型分类器。

- [ ] **Step 1: 修改测试期望为CREATE/EDIT**

关键用例：

```text
无previousDsl → CREATE
有previousDsl，但独立完整新请求 → CREATE
有previousDsl，“改成前10只” → EDIT/SIMPLE
有previousDsl，“增加最大回撤条件” → EDIT/COMPLEX
有previousDsl，“不要之前的，筛选债券基金” → EDIT/COMPLEX
上下文不确定且三层均低置信 → CREATE
```

- [ ] **Step 2: 运行测试确认旧REBUILD语义失败**

Run: `pytest -q tests/unit/test_intent_funnel.py tests/unit/test_semantic_router.py`

Expected: FAIL，因为现有枚举和Graph仍使用REBUILD。

- [ ] **Step 3: 重命名模型与Prompt**

将所有一级 `REBUILD` 改为 `EDIT`。不增加其他一级意图；pending clarification作为Conversation State处理，并路由到EDIT恢复流程。

- [ ] **Step 4: 实现两套下游路由**

```text
CREATE → Direct / Simple / Full
EDIT   → Simple / Complex
```

EDIT Simple直接Patch；EDIT Complex进入EditPlan、只召回新增/替换condition，再应用Patch。

- [ ] **Step 5: 保持默认CREATE原则**

只有 `previousDsl` 存在且Query包含明确修改、延续、删除、替换或上一轮引用证据时才接受EDIT。模型低置信、证据为空或概率差小于0.15时返回CREATE。

- [ ] **Step 6: 运行意图与多轮测试**

Run:

```powershell
pytest -q tests/unit/test_intent_funnel.py tests/unit/test_semantic_router.py tests/unit/test_multi_round_semantic_edit.py tests/unit/test_editor.py
```

Expected: PASS。

---

### Task 8: 改造Embedding文本和alias治理

**Files:**
- Modify: `scripts/data_sync/sync_fund_screening_rag.py`
- Modify: `docs/temp/metrics.json`
- Modify: `docs/temp/sectors.json`
- Test: `fund-screening-service/tests/unit/test_rag_sync_mapping.py`
- Test: `fund-screening-service/tests/unit/test_rag_sync_embedding.py`

**Interfaces:**
- Produces: 稳定的 `generate_metric_embedding_text(item)` 和 `generate_sector_embedding_text(...)`。
- Consumes: 原始embedding_text、结构化alias、名称、说明和板块路径。

- [ ] **Step 1: 写Embedding文本失败测试**

断言：

```python
text = generate_metric_embedding_text({
    "mnemonic": "f_nav_adjustedreturn",
    "index_name": "复权单位净值增长率",
    "description": "指定区间收益",
    "embedding_text": "复权净值增长 区间收益",
    "aliases": ["区间回报", "指定日期区间收益"],
    "parameters": [{"key": "windCode"}, {"key": "startDate"}, {"key": "endDate"}],
    "result": {"valueType": "DECIMAL", "unit": "%"},
})
assert "复权净值增长" in text
assert "区间回报" in text
assert "指定日期区间收益" in text
assert "运算符:" not in text
assert "用法:" not in text
```

- [ ] **Step 2: 运行同步映射测试确认失败**

Run: `pytest -q tests/unit/test_rag_sync_mapping.py tests/unit/test_rag_sync_embedding.py`

Expected: FAIL，因为当前生成器忽略JSON中的embedding_text并加入大量通用字段。

- [ ] **Step 3: 实现唯一的Embedding文本合成规则**

组合并去重：

```text
已有embedding_text
index_name
mnemonic
parent_name
description
aliases
有区分度的时间/业务限定
```

默认排除：supportedOperators、filter/sort、DECIMAL等通用类型和所有指标重复的参数名。参数语义只有在能区分指标时才通过description进入文本。

- [ ] **Step 4: 只补充现有目录alias**

至少修正已讨论Case：

```text
季度/区间收益表达 → 现有区间收益指标的alias与时间语义触发词
合并规模 → 目标现有规模指标alias
ETF → 现有ETF板块alias
```

不得新增或删除任何metric/sector记录。增加测试对修改前后记录ID集合做完全相等断言。

- [ ] **Step 5: 运行同步单元测试和dry-run**

Run:

```powershell
pytest -q tests/unit/test_rag_sync_mapping.py tests/unit/test_rag_sync_embedding.py
python scripts/data_sync/sync_fund_screening_rag.py --dry-run
```

Expected: 测试PASS；dry-run显示指标和板块数量不变。

---

### Task 9: 实现真实候选的确定性优先级和歧义裁决

**Files:**
- Create: `fund-screening-service/app/retrieval/candidate_ranker.py`
- Modify: `fund-screening-service/app/models/candidate.py`
- Modify: `fund-screening-service/app/pipeline/analysis.py`
- Modify: `fund-screening-service/app/retrieval/pgvector.py`
- Modify: `fund-screening-service/app/catalog/loader.py`
- Test: `fund-screening-service/tests/unit/test_candidate_ranker.py`
- Test: `fund-screening-service/tests/unit/test_condition_recall.py`

**Interfaces:**
- Produces: `rank_candidates(mention: str, candidates: Sequence[CandidateContract]) -> list[CandidateContract]`。
- Consumes: name、aliases、default_priority、业务限定、参数可满足性和vector score。

- [ ] **Step 1: 写优先级失败测试**

覆盖：

```text
完整名称匹配优先于alias
alias精确匹配优先于vector score
“ETF”选择默认优先级最高的现有ETF板块
“场内非货币ETF”选择名称限定最匹配的现有板块
“合并规模”选择alias明确包含“合并规模”的现有指标
分数完全相同时按真实ID稳定排序
```

- [ ] **Step 2: 运行测试确认失败**

Run: `pytest -q tests/unit/test_candidate_ranker.py tests/unit/test_condition_recall.py`

Expected: FAIL，因为当前按数据库向量顺序直接返回。

- [ ] **Step 3: 增加候选default_priority元数据**

`CandidateContract`增加整数 `default_priority=0`；loader和pgvector查询加载该字段。若数据库暂时没有列，则优先从目录侧配置注入，避免先引入破坏性迁移。

- [ ] **Step 4: 实现稳定排序键**

排序键依次使用：

```text
exact_name
exact_alias
query_qualifier_coverage
contract_status
parameter_satisfiability
default_priority
retrieval_score
stable_key
```

Ranker只能重排真实候选，不生成、合并或删除目录对象；相同key去重允许保留最高分来源。

- [ ] **Step 5: 在byCondition构建前应用Ranker**

`RecallByConditionService`对每个condition独立排序，保留原始分数、最终rank及选择原因供Trace和评估使用。

- [ ] **Step 6: 运行候选与召回测试**

Run:

```powershell
pytest -q tests/unit/test_candidate_ranker.py tests/unit/test_condition_recall.py tests/unit/test_pgvector_retrieval.py
```

Expected: PASS。

---

### Task 10: Validator和Finalize执行契约加固

**Files:**
- Modify: `fund-screening-service/app/pipeline/validator.py`
- Modify: `fund-screening-service/app/graph/nodes.py`
- Modify: `fund-screening-service/app/graph/repair.py`
- Test: `fund-screening-service/tests/unit/test_hardened_validator.py`
- Test: `fund-screening-service/tests/unit/test_graph_repair_loop.py`

**Interfaces:**
- Produces: Finalize前唯一执行契约门禁。
- Consumes: 新DSL节点、CandidateContract、DynamicDefaultResolver。

- [ ] **Step 1: 写拒绝非法最终DSL的失败测试**

分别构造并拒绝：

```text
指标叶子value缺失
unit=DECIMAL
params日期为LATEST_TRADE_DATE
Group sort为空
Root sort为空
Group limit缺失
SectorLeaf包含未知字段match
指标ID不在contractIndex
```

- [ ] **Step 2: 运行加固测试确认失败**

Run: `pytest -q tests/unit/test_hardened_validator.py`

Expected: FAIL，证明当前Validator放行至少部分非法结构。

- [ ] **Step 3: 实现递归叶子校验和可确定修复**

只允许修复：

```text
单位换算
枚举alias转整数
日期格式标准化
动态默认解析
负值存储口径
默认sort/limit
单子节点逻辑树收缩
```

缺少value、未知指标或无法解析日期必须clarify/reject，禁止猜测。

- [ ] **Step 4: Finalize执行完整门禁**

Finalize只有在：

```text
validation.status in {ACCEPT, REPAIRED}
validation_errors为空
不存在LATEST_*占位符
所有Group和Root分页完整
```

时才能设置 `final_dsl`。

- [ ] **Step 5: 运行Validator和修复循环测试**

Run:

```powershell
pytest -q tests/unit/test_hardened_validator.py tests/unit/test_validator.py tests/unit/test_graph_repair_loop.py
```

Expected: PASS。

---

### Task 11: 更新Golden评估为最终选择和参数正确性

**Files:**
- Modify: `fund-screening-service/app/evaluation/schema.py`
- Modify: `fund-screening-service/app/evaluation/metrics.py`
- Modify: `fund-screening-service/app/evaluation/runner.py`
- Modify: `fund-screening-service/data/golden/*`
- Test: `fund-screening-service/tests/unit/test_ranking_metrics.py`
- Test: `fund-screening-service/tests/integration/test_golden_evaluation_pipeline.py`

**Interfaces:**
- Produces: 精确Top-K、最终选择准确率、参数准确率和DSL可执行率。
- Consumes: byCondition候选、Adjudication binding和finalDsl。

- [ ] **Step 1: 扩展Golden Case Schema**

每个Case至少支持：

```json
{
  "query": "筛选季度收益大于10%的基金",
  "asOfDate": "20260930",
  "expected": {
    "metric": "f_nav_adjustedreturn",
    "operator": ">",
    "value": 10,
    "unit": "%",
    "params": {
      "startDate": "20260701",
      "endDate": "20260930"
    }
  }
}
```

- [ ] **Step 2: 增加三个已讨论的Golden Case**

```text
季度收益 → 区间回报真实指标及日期参数
合并规模 → 唯一目标真实指标
ETF → 默认优先级选中的唯一真实板块
```

- [ ] **Step 3: 修改评估指标**

同时报告：

```text
Raw Recall@1/@3
Final Candidate Selection Accuracy
Operator/Value/Unit Accuracy
Parameter Accuracy
Executable DSL Rate
```

不再把“业务可能相近”作为自动正确；只有Golden允许的真实ID才算命中。

- [ ] **Step 4: 运行Golden评估测试**

Run:

```powershell
pytest -q tests/unit/test_ranking_metrics.py tests/integration/test_golden_evaluation_pipeline.py
```

Expected: PASS并产出各层指标。

---

### Task 12: 端到端回归与真实依赖验收

**Files:**
- Modify: `fund-screening-service/tests/integration/test_semantic_boundary_hardening_acceptance.py`
- Modify: `fund-screening-service/tests/integration/test_real_graph_smoke.py`
- Modify: `fund-screening-service/README.md`

**Interfaces:**
- Consumes: 前11项全部接口。
- Produces: 可交付的HTTP/WebSocket/RAG/LLM端到端验证记录。

- [ ] **Step 1: 运行最窄业务验收**

Run:

```powershell
cd fund-screening-service
pytest -q tests/integration/test_executable_dsl_contract.py
pytest -q tests/integration/test_semantic_boundary_hardening_acceptance.py
```

Expected: PASS，且“规模大于10亿且成立超过5年”输出符合Task 1全部断言。

- [ ] **Step 2: 运行全量Python测试**

Run:

```powershell
pytest -q
cd ..
python -m unittest discover -s scripts/data_sync/tests -p "test_*.py"
```

Expected: 全部非真实依赖测试PASS，无新增skip。

- [ ] **Step 3: 检查真实依赖状态**

Run:

```powershell
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

仅在PostgreSQL/pgvector、Embedding、LLM Bridge和Redis实际可用时继续真实Smoke；不可用时记录缺失依赖，不伪报通过。

- [ ] **Step 4: 重建并验证RAG索引**

Run:

```powershell
python scripts/data_sync/sync_fund_screening_rag.py
```

Expected: enabled metric/sector数量与目录一致、全部向量维度1024、Top-K健康查询通过。

- [ ] **Step 5: 运行真实Graph Smoke**

Run:

```powershell
$env:RUN_REAL_FUND_SCREENING_TESTS = "1"
pytest -q tests/integration/test_real_graph_smoke.py
```

Expected: CREATE Direct、CREATE Simple、CREATE Full、EDIT Simple、EDIT Complex均通过；最终DSL不含非法单位和动态占位符。

- [ ] **Step 6: 验证HTTP和WebSocket一致性**

对同一query和固定asOfDate分别调用HTTP与WebSocket，断言最终DSL深度相等，并校验两级sort/limit、日期和单位。

- [ ] **Step 7: 更新README为真实能力**

README明确记录：

```text
CREATE/EDIT意图模型
CREATE Direct/Simple/Full与EDIT Simple/Complex
Embedding文本实际字段
alias修改后的索引重建命令
时间区间指标回退规则
Group/Root双层sort/limit
动态日期最终解析规则
真实依赖Smoke启动方式
```

- [ ] **Step 8: 最终差异和未授权提交检查**

Run:

```powershell
git status --short
git diff --check
```

Expected: 无空白错误；保留用户原有改动；未经用户明确授权不执行commit、merge或push。

---

## Implementation Order and Review Gates

严格按以下顺序实施：

```text
Task 1 失败验收基线
→ Task 2 DSL类型边界
→ Task 3 裁决契约
→ Task 4 双层排序截断
→ Task 5 动态日期
→ Task 6 时间指标解析
→ Task 7 意图与路由
→ Task 8 Embedding/alias
→ Task 9 候选优先级
→ Task 10 Validator门禁
→ Task 11 Golden评估
→ Task 12 端到端验收
```

每完成一个Task，必须先运行该Task列出的最窄测试并检查差异；不得在同一个未经验证的批次中同时修改DSL模型、LLM Prompt、日期解析和召回排序。

## Self-Review Result

- Spec coverage：覆盖CREATE/EDIT意图、CREATE三级和EDIT两级分流、SemanticPlan唯一逻辑来源、Condition级召回、真实候选选择、alias/Embedding、时间区间回退、DSL叶子边界、双层sort/limit、动态日期、单位、Validator、Golden评估和真实依赖验收。
- Placeholder scan：计划中不存在占位标记或未定义实现步骤。
- Type consistency：统一使用 `IntentDecision`、`SemanticPlan`、`ResolvedCondition`、`CandidateContract`、`MetricPredicate`、`MetricLogicNode`、`DynamicDefaultResolver`、`MetricResolutionPolicy` 和 `rank_candidates`。
- Repository discipline：计划显式禁止未经授权提交，符合当前工作树规则。
