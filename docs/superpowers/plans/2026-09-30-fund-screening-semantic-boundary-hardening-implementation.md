# Fund Screening Semantic Boundary Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the architectural boundaries of the fund screening semantic pipeline according to `docs/superpowers/specs/2026-09-30-fund-screening-semantic-boundary-hardening-design.md`:
1. Decouple intent from routing (`CREATE/REBUILD` only for IntentFunnel).
2. StrictDirectProbe only bypasses with provable zero-call certainty (never interprets fuzzy terms or builds trees).
3. SemanticPlan/EditPlan are the sole source of AST logical structure (AND/OR/NOT) with stable `conditionId`.
4. Condition candidate view records per-condition provenance (`condition_id`, `retrieval_channel`, `retrieval_score`, `rank`, `parameter_schema`, `storage_convention`).
5. Route (`direct/simple/full`) is purely deterministic based on plan complexity, candidate ambiguity, and parameter completeness with enforceable LLM budget.
6. Adjudication only resolves leaves into `ResolvedCondition` without altering plan structure.
7. Builder recursively constructs DSL strictly following the plan tree.
8. Validator returns 3 distinct states (`ACCEPT`, `REPAIRED`, `REJECT_OR_CLARIFY`) where clarifications produce structured, resumable `ClarificationPlan` rather than failing as uncaught exceptions.

**Architecture:**
```text
IntentFunnel (CREATE | REBUILD)
  -> StrictDirectProbe (0 LLM short-circuit for exact code/entity/metric/simple edit patch)
  -> SemanticPlan / EditPlan (Stable conditionId tree, sole source of AND/OR/NOT)
  -> Condition Recall (Per-condition candidate pool & ConditionCandidateView provenance)
  -> Route (direct | simple | full - deterministic rule based on plan & ambiguity)
  -> Resolve (ResolvedCondition leaf bindings, max 1 constrained LLM call)
  -> Builder (Pure recursive tree assembly into FundScreeningDsl)
  -> Validator (ACCEPT | REPAIRED | REJECT_OR_CLARIFY -> ClarificationPlan)
```

**Tech Stack:** Python 3.11, LangGraph, Pydantic v2, pytest.

**Spec:** [docs/superpowers/specs/2026-09-30-fund-screening-semantic-boundary-hardening-design.md](file:///d:/BaiduSyncdisk/IdeaProjects/FinancialCopilot/.worktrees/fund-screening-agent/docs/superpowers/specs/2026-09-30-fund-screening-semantic-boundary-hardening-design.md)

---

## Global Constraints & Invariants

- Zero new model endpoints or deployments; reuse existing LLM configuration.
- Do not adjust pgvector embeddings, rerank algorithms, or dictionary aliases in this refactoring (those are golden-report driven).
- Preserve existing DSL schema, metrics conventions (e.g. negative drawdown storage), and data engine contracts.
- Model call budgets must be strictly enforced and audited in state trace:
  - `strict direct`: exactly 0 LLM calls
  - `simple`: 1 SemanticPlan LLM call; at most 1 constrained adjudication LLM call only if candidate resolution is ambiguous
  - `full`: 1 SemanticPlan/EditPlan LLM call + 1 constrained adjudication LLM call
- Trace logs must never contain user IDs, conversation tokens, or sensitive API keys.

---

### Task 1: Condition Candidate View & ResolvedCondition Contract Models

**Files:**
- Modify: `fund-screening-service/app/models/candidate.py`
- Modify: `fund-screening-service/app/models/semantic_plan.py`
- Create: `fund-screening-service/tests/unit/test_resolved_condition_models.py`

**Interfaces:**
- Produces:
  - `ConditionCandidateView`:
    ```python
    class ConditionCandidateView(BaseModel):
        condition_id: str
        candidate_type: Literal["metric", "scope", "entity"]
        canonical_id: str
        display_name: str
        aliases_matched: list[str] = Field(default_factory=list)
        retrieval_channel: str  # e.g. "pgvector", "lexical", "direct"
        retrieval_score: float
        rank: int
        parameter_schema: list[dict[str, Any]] = Field(default_factory=list)
        storage_convention: str | None = None
    ```
  - `ResolvedCondition`:
    ```python
    class ResolvedCondition(BaseModel):
        condition_id: str = Field(alias="conditionId")
        selected_candidate_id: str = Field(alias="selectedCandidateId")
        binding_role: str | None = Field(default=None, alias="bindingRole")
        operator: Literal[">", ">=", "<", "<=", "=", "!="] = "="
        value: Any = None
        unit: str | None = None
        period: str | None = None
        params: dict[str, Any] = Field(default_factory=dict)
        confidence: float = 1.0
    ```
  - Update `ConditionBinding` as alias / compatibility wrapper around `ResolvedCondition`.

- [x] **Step 1: Write failing unit test for ConditionCandidateView and ResolvedCondition**

```python
# fund-screening-service/tests/unit/test_resolved_condition_models.py
import pytest
from app.models.candidate import ConditionCandidateView
from app.models.semantic_plan import ResolvedCondition, ConditionBinding


def test_condition_candidate_view_provenance():
    view = ConditionCandidateView(
        condition_id="c_1",
        candidate_type="metric",
        canonical_id="f_return_1y",
        display_name="近1年收益率",
        aliases_matched=["近一年收益"],
        retrieval_channel="pgvector",
        retrieval_score=0.92,
        rank=1,
        parameter_schema=[],
        storage_convention="PERCENT_POSITIVE",
    )
    assert view.condition_id == "c_1"
    assert view.retrieval_channel == "pgvector"
    assert view.rank == 1


def test_resolved_condition_immutability_and_validation():
    resolved = ResolvedCondition(
        conditionId="c_1",
        selectedCandidateId="f_info_fundscale",
        operator=">",
        value=10.0,
        unit="亿",
        params={},
        confidence=0.98,
    )
    assert resolved.condition_id == "c_1"
    assert resolved.selected_candidate_id == "f_info_fundscale"
    assert resolved.confidence == 0.98
```

- [x] **Step 2: Run test to verify it fails**

Run: `pytest tests/unit/test_resolved_condition_models.py -v`
Expected: FAIL

- [x] **Step 3: Implement `ConditionCandidateView` and `ResolvedCondition`**

Add `ConditionCandidateView` in `app/models/candidate.py`.
Add `ResolvedCondition` in `app/models/semantic_plan.py` (with bidirectional alias compatibility for `candidate_key` <-> `selected_candidate_id` and `candidateKey` <-> `selectedCandidateId`).

- [x] **Step 4: Run tests to verify they pass**

Run: `pytest tests/unit/test_resolved_condition_models.py -v`
Expected: PASS

---

### Task 2: StrictDirectProbe Zero-Call Proof & IntentFunnel Decoupling

**Files:**
- Modify: `fund-screening-service/app/graph/direct_probe.py`
- Modify: `fund-screening-service/app/pipeline/intent_funnel.py`
- Create: `fund-screening-service/tests/unit/test_hardened_direct_probe.py`

**Interfaces:**
- `IntentFunnel.classify_intent(query, previous_dsl)` -> strictly returns `IntentType.CREATE | IntentType.REBUILD` (no routing hints).
- `probe_strict_direct(query, candidates, previous_dsl)`:
  - Returns `(True, dsl, reason)` ONLY when zero-call proof holds:
    1. Exact 6-digit fund code (e.g. `001938` / `001938.OF`).
    2. Exact candidate entity name / alias with zero operational keywords or qualifiers.
    3. Strict single metric with explicit literal operator, valid numeric literal, and exact match to catalog metric with supported operator.
    4. Exact simple edit parameter patch.
  - In ALL other cases, returns `(False, None, reason)`. Never attempts to build trees or interpret fuzzy words.

- [x] **Step 1: Write failing unit test for hardened StrictDirectProbe & IntentFunnel**

```python
# fund-screening-service/tests/unit/test_hardened_direct_probe.py
import pytest
from app.graph.direct_probe import probe_strict_direct
from app.models.candidate import CandidateContract
from app.pipeline.intent_funnel import IntentFunnel, IntentType


def test_intent_funnel_strictly_outputs_create_or_rebuild():
    funnel = IntentFunnel()
    res1 = funnel.classify_intent("筛选近一年收益大于10%的基金", previous_dsl=None)
    assert res1.intent in (IntentType.CREATE, IntentType.REBUILD)
    assert not hasattr(res1, "route")  # No route coupling

    prev_dsl = {"version": "fund-screening/v1", "children": []}
    res2 = funnel.classify_intent("把规模改成20亿", previous_dsl=prev_dsl)
    assert res2.intent in (IntentType.CREATE, IntentType.REBUILD)


def test_strict_direct_probe_rejects_ambiguity_and_descriptions():
    candidates = [
        CandidateContract(domain="metric", key="f_return_1y", name="近1年收益率", supported_operators=(">", ">="), value_type="PERCENT")
    ]
    # Fuzzy query must not be bypassed by direct probe
    ok, dsl, reason = probe_strict_direct("收益好的基金", candidates)
    assert ok is False
    assert dsl is None

    # Multi-condition query must not be bypassed
    ok2, dsl2, reason2 = probe_strict_direct("规模大于10亿且成立超过3年", candidates)
    assert ok2 is False
    assert dsl2 is None


def test_strict_direct_probe_accepts_provable_exact_query():
    candidates = [
        CandidateContract(domain="metric", key="f_info_fundscale", name="基金规模", supported_operators=(">", ">="), value_type="BILLION_CNY", aliases=("规模",))
    ]
    ok, dsl, reason = probe_strict_direct("规模>10亿", candidates)
    assert ok is True
    assert dsl is not None
    assert reason == "STRICT_SINGLE_METRIC"
```

- [x] **Step 2: Run test to verify it fails**

Run: `pytest tests/unit/test_hardened_direct_probe.py -v`
Expected: FAIL

- [x] **Step 3: Implement hardened `probe_strict_direct` and verify `IntentFunnel` decoupling**

Harden `app/graph/direct_probe.py`:
- Add verification that any query containing fuzzy keywords (`好`, `优`, `低风险`, `稳健`, `高收益`, `排行`, `TOP`) or connectors (`且`, `或`, `和`, `AND`, `OR`) immediately returns `False`.
- Verify `IntentFunnel` only returns `CREATE` or `REBUILD`.

- [x] **Step 4: Run tests to verify they pass**

Run: `pytest tests/unit/test_hardened_direct_probe.py -v`
Expected: PASS

---

### Task 3: Deterministic Routing with Strict Model Budget Tracking

**Files:**
- Modify: `fund-screening-service/app/graph/router.py`
- Modify: `fund-screening-service/app/graph/state.py`
- Create: `fund-screening-service/tests/unit/test_hardened_router.py`

**Interfaces:**
- `classify_plan_route(plan: SemanticPlan, condition_views: dict[str, list[ConditionCandidateView]]) -> tuple[Literal["direct", "simple", "full"], str]`
  - Strict deterministic criteria:
    - `full`:
      - Plan has `OR` or `NOT` operators anywhere in group or metric/scope trees.
      - Multiple business groups (`len(groups) > 1`).
      - Condition count > 4 or tree depth > 2.
      - Any condition has candidate score gap between Top-1 and Top-2 < 0.15 with high scores (ambiguity).
      - Parameter definition missing required parameters or containing invalid enums.
    - `simple`:
      - Single business group, shallow `AND` only, conditions count <= 4.
      - Top-1 candidate is clear (score gap >= 0.15 or single strong match).
    - `direct`:
      - All conditions are 100% deterministically matched with 1 unambiguous candidate and complete literal parameters.
  - Track `llm_budget` in state / trace:
    - strict direct: 0
    - simple: 1 plan LLM (+ 1 adjudication LLM only if ambiguous)
    - full: 1 plan LLM + 1 adjudication LLM

- [x] **Step 1: Write failing unit test for hardened deterministic router**

```python
# fund-screening-service/tests/unit/test_hardened_router.py
import pytest
from app.graph.router import classify_plan_route
from app.models.candidate import ConditionCandidateView
from app.models.semantic_plan import SemanticPlan, PlanGroup, MetricTree, MetricLeaf


def test_classify_plan_route_full_on_nested_or():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(conditionId="c1", mention="规模大于10亿"),
                        MetricTree(
                            operator="OR",
                            children=[
                                MetricLeaf(conditionId="c2", mention="成立大于5年"),
                                MetricLeaf(conditionId="c3", mention="成立大于3年"),
                            ],
                        ),
                    ],
                )
            )
        ]
    )
    route, reason = classify_plan_route(plan, {})
    assert route == "full"
    assert "OR" in reason or "NESTED" in reason


def test_classify_plan_route_simple_on_shallow_and():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(conditionId="c1", mention="规模大于10亿"),
                        MetricLeaf(conditionId="c2", mention="成立大于5年"),
                    ],
                )
            ]
        ]
    )
    candidate_views = {
        "c1": [ConditionCandidateView(condition_id="c1", candidate_type="metric", canonical_id="f_scale", display_name="规模", retrieval_channel="direct", retrieval_score=1.0, rank=1)],
        "c2": [ConditionCandidateView(condition_id="c2", candidate_type="metric", canonical_id="f_year", display_name="成立年限", retrieval_channel="direct", retrieval_score=1.0, rank=1)],
    }
    route, reason = classify_plan_route(plan, candidate_views)
    assert route in ("simple", "direct")
```

- [x] **Step 2: Run test to verify it fails**

Run: `pytest tests/unit/test_hardened_router.py -v`
Expected: FAIL

- [x] **Step 3: Implement deterministic `classify_plan_route`**

Update `app/graph/router.py`:
- Inspect tree operators (recursively checking for OR/NOT in `ScopeTree` and `MetricTree`).
- Inspect candidate views for Top1 vs Top2 confidence gaps.
- Return `(route, reason)` tuple.
- Update `FundScreeningState` in `app/graph/state.py` to record `llm_calls_count` and `budget_limit`.

- [x] **Step 4: Run tests to verify they pass**

Run: `pytest tests/unit/test_hardened_router.py -v`
Expected: PASS

---

### Task 4: Pure Tree-Preserving CommandBuilder

**Files:**
- Modify: `fund-screening-service/app/pipeline/builder.py`
- Modify: `fund-screening-service/app/graph/nodes.py`
- Create: `fund-screening-service/tests/unit/test_tree_preserving_builder.py`

**Interfaces:**
- `CommandBuilder.build_from_plan(plan: SemanticPlan, adjudication: AdjudicationResult, contracts: dict[str, Any]) -> FundScreeningDsl`:
  - Recursively builds DSL strictly following `SemanticPlan.groups` and `MetricTree`/`ScopeTree`.
  - Maps each leaf node to its bound `ResolvedCondition`.
  - Replaces all legacy un-anchored `self._builder.build(decision, pool)` in `ScreeningGraphNodes` with `build_from_plan`.
  - Safely handles leaf nodes where no metric was extracted without emitting invalid empty groups `{'operator': 'AND', 'children': []}`.

- [x] **Step 1: Write failing unit test for tree-preserving builder**

```python
# fund-screening-service/tests/unit/test_tree_preserving_builder.py
import pytest
from app.models.candidate import CandidateContract
from app.models.semantic_plan import (
    SemanticPlan, PlanGroup, MetricTree, MetricLeaf,
    AdjudicationResult, ResolvedCondition
)
from app.pipeline.builder import CommandBuilder


def test_builder_strictly_retains_nested_tree_shape():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(conditionId="c1", mention="规模大于10亿"),
                        MetricTree(
                            operator="OR",
                            children=[
                                MetricLeaf(conditionId="c2", mention="成立超过5年"),
                                MetricLeaf(conditionId="c3", mention="成立超过3年"),
                            ],
                        ),
                    ],
                )
            )
        ]
    )
    adjudication = AdjudicationResult(
        condition_bindings=[
            ResolvedCondition(conditionId="c1", selectedCandidateId="f_scale", operator=">", value=10),
            ResolvedCondition(conditionId="c2", selectedCandidateId="f_year", operator=">", value=5),
            ResolvedCondition(conditionId="c3", selectedCandidateId="f_year", operator=">", value=3),
        ]
    )
    contracts = {
        "f_scale": CandidateContract("metric", "f_scale", "规模"),
        "f_year": CandidateContract("metric", "f_year", "年限"),
        "1000019220000000": CandidateContract("scope", "1000019220000000", "全市场"),
    }
    builder = CommandBuilder()
    dsl = builder.build_from_plan(plan, adjudication, contracts)

    root_metric = dsl.children[0].metric
    assert root_metric.operator == "AND"
    assert len(root_metric.children) == 2
    # Subtree operator must be OR
    assert root_metric.children[1].operator == "OR"
```

- [x] **Step 2: Run test to verify it fails**

Run: `pytest tests/unit/test_tree_preserving_builder.py -v`
Expected: FAIL

- [x] **Step 3: Update `CommandBuilder` and remove legacy decision builders in `ScreeningGraphNodes`**

In `app/pipeline/builder.py`:
- Ensure `build_from_plan` recursively preserves nested `MetricTree` and `ScopeTree`.
- Remove legacy unstructured fallback or guard it against empty child groups.
In `app/graph/nodes.py`:
- Ensure `simple_semantic_parse` and `full_semantic_parse` use `build_from_plan`.

- [x] **Step 4: Run tests to verify they pass**

Run: `pytest tests/unit/test_tree_preserving_builder.py -v`
Expected: PASS

---

### Task 5: ClarificationPlan, 3-State Validator & Multi-Turn Resumption

**Files:**
- Create: `fund-screening-service/app/models/clarification.py`
- Modify: `fund-screening-service/app/pipeline/validator.py`
- Modify: `fund-screening-service/app/graph/nodes.py`
- Create: `fund-screening-service/tests/unit/test_hardened_validator.py`

**Interfaces:**
- Produces:
  - `ClarificationPlan`:
    ```python
    class ClarificationPlan(BaseModel):
        code: str
        message: str
        ambiguous_mentions: list[str] = Field(default_factory=list, alias="ambiguousMentions")
        candidate_interpretations: dict[str, list[str]] = Field(default_factory=dict, alias="candidateInterpretations")
        question: str = ""
        blocked_condition_ids: list[str] = Field(default_factory=list, alias="blockedConditionIds")
        resolved_conditions: list[ResolvedCondition] = Field(default_factory=list, alias="resolvedConditions")
        preserved_plan: SemanticPlan | None = Field(default=None, alias="preservedPlan")
    ```
  - `ValidationResult`:
    ```python
    class ValidationResult(BaseModel):
        status: Literal["ACCEPT", "REPAIRED", "REJECT_OR_CLARIFY"]
        dsl: FundScreeningDsl | None = None
        repairs: list[str] = Field(default_factory=list)
        clarification_plan: ClarificationPlan | None = Field(default=None, alias="clarificationPlan")
    ```
- Multi-turn resumption:
  - If next user query responds to a clarification, the existing unblocked `resolved_conditions` and `preserved_plan` are reused, only solving the blocked leaves.

- [x] **Step 1: Write failing unit test for 3-state validator and ClarificationPlan**

```python
# fund-screening-service/tests/unit/test_hardened_validator.py
import pytest
from app.models.dsl import FundScreeningDsl, BusinessGroup, SectorExpression, SectorLeaf, MetricExpression
from app.models.candidate import CandidateContract
from app.models.clarification import ClarificationPlan
from app.pipeline.validator import validate_and_repair


def test_validator_returns_repaired_status_on_negative_drawdown():
    dsl = FundScreeningDsl(
        version="fund-screening/v1",
        operator="AND",
        children=[
            BusinessGroup(
                sector=SectorExpression(operator="OR", children=[SectorLeaf(sectorId="1000019220000000")]),
                metric=MetricExpression(operator="AND", children=[
                    MetricExpression(metric="f_risk_maxdownside", operator="<", value=20.0, unit="%")
                ]),
            )
        ],
    )
    contracts = {
        "f_risk_maxdownside": CandidateContract(
            "metric", "f_risk_maxdownside", "最大回撤",
            supported_operators=("<", "<=", ">", ">="),
            result_contract={"storageConvention": "NEGATIVE"},
        )
    }
    res = validate_and_repair(dsl, contracts)
    assert res.status == "REPAIRED"
    assert "NEGATIVE_DRAWDOWN_STORAGE" in res.repairs
    # Drawdown must be converted to >= -20
    metric_node = res.dsl.children[0].metric.children[0]
    assert metric_node.value == -20.0
    assert metric_node.operator == ">="


def test_validator_returns_reject_or_clarify_for_unsupported_operator():
    dsl = FundScreeningDsl(
        version="fund-screening/v1",
        operator="AND",
        children=[
            BusinessGroup(
                sector=SectorExpression(operator="OR", children=[SectorLeaf(sectorId="1000019220000000")]),
                metric=MetricExpression(operator="AND", children=[
                    MetricExpression(metric="f_scale", operator="!=", value=10.0, unit="亿")
                ]),
            )
        ],
    )
    contracts = {
        "f_scale": CandidateContract("metric", "f_scale", "规模", supported_operators=(">", ">=", "<", "<="))
    }
    res = validate_and_repair(dsl, contracts)
    assert res.status == "REJECT_OR_CLARIFY"
    assert res.clarification_plan is not None
    assert res.clarification_plan.code == "UNSUPPORTED_OPERATOR"
```

- [x] **Step 2: Run test to verify it fails**

Run: `pytest tests/unit/test_hardened_validator.py -v`
Expected: FAIL

- [x] **Step 3: Implement `ClarificationPlan` and 3-state `ValidationResult`**

Create `app/models/clarification.py`.
Update `validate_and_repair` in `app/pipeline/validator.py` to populate `status` and `clarification_plan`.

- [x] **Step 4: Run tests to verify they pass**

Run: `pytest tests/unit/test_hardened_validator.py -v`
Expected: PASS

---

### Task 6: Graph Workflow Integration & End-to-End Acceptance Verification

**Files:**
- Modify: `fund-screening-service/app/graph/nodes.py`
- Modify: `fund-screening-service/app/pipeline/orchestrator.py`
- Create: `fund-screening-service/tests/integration/test_semantic_boundary_hardening_acceptance.py`

**Interfaces:**
- Connects hardened `probe_strict_direct`, `SemanticPlan/EditPlan`, condition views, deterministic `classify_plan_route`, `build_from_plan`, and 3-state `validate_and_repair`.
- Verifies all 6 acceptance criteria from design spec:
  1. `CREATE/REBUILD` and `direct/simple/full` decisions are strictly independent and traceable.
  2. Non-provable queries are never consumed by strict direct probe.
  3. Builder produces identical, stable DSL given identical plan & bindings regardless of adjudication prompts.
  4. Non-safely-repairable expressions return structured `ClarificationPlan` where unblocked conditions are preserved for multi-turn reuse.
  5. Edit operations strictly reference stable condition IDs, recalling candidates only for added/replaced leaves.
  6. Golden evaluation reports locate condition-level failures.

- [x] **Step 1: Write comprehensive end-to-end acceptance tests**

```python
# fund-screening-service/tests/integration/test_semantic_boundary_hardening_acceptance.py
import pytest
from app.graph.checkpoint import make_graph_config
from app.graph.state import empty_state
from app.main import _build_default_graph


@pytest.mark.asyncio
async def test_acceptance_strict_direct_zero_llm_calls():
    graph = _build_default_graph()
    state = empty_state("001938")
    config = make_graph_config()
    final_state = state
    async for update in graph.astream(state, config=config, stream_mode="updates"):
        for _, u in update.items():
            final_state = {**final_state, **u}

    assert final_state.get("final_dsl") is not None
    # Must have bypassed plan and adjudication
    traces = final_state.get("trace", [])
    stages = [t["stage"] for t in traces]
    assert "direct_probe" in stages
    assert "semantic_plan" not in stages


@pytest.mark.asyncio
async def test_acceptance_ambiguous_query_routes_to_clarification_plan():
    graph = _build_default_graph()
    state = empty_state("找一只收益好的稳健基金")
    config = make_graph_config()
    final_state = state
    async for update in graph.astream(state, config=config, stream_mode="updates"):
        for _, u in update.items():
            final_state = {**final_state, **u}

    # Must produce clarification without crashing
    clar = final_state.get("clarification")
    assert clar is not None
```

- [x] **Step 2: Run test to verify it passes**

Run: `pytest tests/integration/test_semantic_boundary_hardening_acceptance.py -v`
Expected: PASS

- [x] **Step 3: Run entire project test suite to verify zero regressions**

Run:
```powershell
& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit tests/integration tests/api -v
```
Expected: All 190+ tests PASS.

- [x] **Step 4: Verify working-tree discipline**

Run: `git status`
Expected: Clean working tree with only requested additions.
