# Fund Screening Unified Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Converge Strict Direct, legacy RecallPlan fallback, and SemanticPlan paths into a single observable LangGraph compilation pipeline with zero-hallucination candidate-constrained adjudication, deterministic AST DSL building, condition-level incremental editing via EditPlan, and unified LangGraph entry for HTTP and WebSocket.

**Architecture:**
1. **Intent Funnel & Strict Rule Bypass:** Pure fund codes, exact entities, and `REBUILD_SIMPLE` bypass Plan LLM directly to deterministic DSL without LLM invocation.
2. **Plan LLM (LLM #1):** `CREATE` outputs `SemanticPlan` (groups, logic trees, mentions, NO business IDs); `REBUILD_COMPLEX` outputs `EditPlan` (`ADD`, `REPLACE`, `REMOVE`, `CHANGE_OPERATOR`, `RESET`), patching the previous plan and leaving untouched conditions intact.
3. **Condition-level RAG:** Only new or replaced leaf conditions undergo vector/lexical retrieval; outputs `byCondition` candidate pool and global `contractIndex`.
4. **Router & Adjudication (LLM #2):** Routes to Direct, Simple, or Full. Candidate Adjudication strictly binds candidate keys and contract parameters for leaf conditions without altering logic trees.
5. **Deterministic AST DSL Builder:** Reassembles recursive AST DSL from Plan logic trees and bindings.
6. **Validator & Repair:** Validates schema/parameters/operators/units/storage conventions and performs mechanical repairs or emits condition-tagged clarifications.
7. **Production Migration:** Retires legacy `RecallPlan` fallback in `app/runtime.py`, `app/graph/nodes.py`, and `app/main.py`.

**Tech Stack:** Python 3.11, FastAPI, LangGraph, LangChain, Pydantic v2, pytest.

**Spec:** [docs/superpowers/specs/2026-09-29-fund-screening-unified-pipeline-design.md](file:///d:/BaiduSyncdisk/IdeaProjects/FinancialCopilot/.worktrees/fund-screening-agent/docs/superpowers/specs/2026-09-29-fund-screening-unified-pipeline-design.md)

## Global Constraints

- Pure fund codes, unique exact entities, and `REBUILD_SIMPLE` requests MUST bypass Plan LLM (0 LLM calls).
- Non-bypass requests MUST invoke Plan LLM at most once, and Candidate Adjudication at most once.
- `REBUILD_COMPLEX` MUST NOT alter or re-retrieve unaffected conditions.
- LLM outputs MUST NOT hallucinate business IDs (metric keys, sector IDs, entity IDs).
- Every compilation cycle MUST preserve full checkpoint state: Plan, Candidate Pool, Adjudication Bindings, DSL, Clarification, and Trace events.
- Both HTTP (`POST /commands`) and WebSocket (`/ws`) MUST execute through the unified LangGraph compilation pipeline.

---

### Task 1: `EditPlan` Models & Patching Engine

**Files:**
- Create: `fund-screening-service/app/models/edit_plan.py`
- Modify: `fund-screening-service/app/models/semantic_plan.py`
- Modify: `fund-screening-service/app/pipeline/editor.py`
- Test: `fund-screening-service/tests/unit/test_edit_plan.py`

**Interfaces:**
- Consumes: `SemanticPlan`, `PlanGroup`, `MetricLeaf`, `ScopeLeaf`, `MetricTree`, `ScopeTree` from `app.models.semantic_plan`.
- Produces:
  - `EditAction = Literal["ADD", "REPLACE", "REMOVE", "CHANGE_OPERATOR", "RESET"]`
  - `EditOperation` model: `action`, `target_condition_id`, `group_id`, `condition` (MetricLeaf | ScopeLeaf), `operator`
  - `EditPlan` model: `intent="REBUILD"`, `operations: list[EditOperation]`, `sort_mentions`, `limit`
  - `apply_edit_plan(previous_plan: SemanticPlan, edit_plan: EditPlan) -> tuple[SemanticPlan, list[str]]` (returns updated plan and list of affected condition IDs to recall)
  - `summarize_plan_for_edit(plan: SemanticPlan) -> dict[str, Any]`

- [ ] **Step 1: Write the failing unit tests for `EditPlan` and `apply_edit_plan`**

```python
# fund-screening-service/tests/unit/test_edit_plan.py
import pytest
from app.models.semantic_plan import (
    SemanticPlan,
    PlanGroup,
    MetricTree,
    MetricLeaf,
    ScopeTree,
    ScopeLeaf,
    SortMention,
)
from app.models.edit_plan import EditPlan, EditOperation
from app.pipeline.editor import apply_edit_plan, summarize_plan_for_edit


@pytest.fixture
def base_plan():
    return SemanticPlan(
        intent="CREATE",
        groups=[
            PlanGroup(
                groupId="g1",
                scopeTree=ScopeTree(operator="OR", children=[ScopeLeaf(conditionId="s1", mention="偏股混合型")]),
                metricTree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(conditionId="m1", mention="规模大于10亿", operatorHint=">", valueHint=10.0, unitHint="亿"),
                        MetricLeaf(conditionId="m2", mention="成立年限大于3年", operatorHint=">", valueHint=3.0, unitHint="年"),
                    ],
                ),
            )
        ],
        sortMentions=[SortMention(mention="规模", direction="DESC")],
        limit=20,
    )


def test_summarize_plan_for_edit(base_plan):
    summary = summarize_plan_for_edit(base_plan)
    assert len(summary["conditions"]) == 3
    c_ids = [c["conditionId"] for c in summary["conditions"]]
    assert "s1" in c_ids and "m1" in c_ids and "m2" in c_ids


def test_apply_edit_plan_replace_condition(base_plan):
    edit = EditPlan(
        operations=[
            EditOperation(
                action="REPLACE",
                targetConditionId="m1",
                condition=MetricLeaf(conditionId="m1_rep", mention="规模大于20亿", operatorHint=">", valueHint=20.0, unitHint="亿"),
            )
        ]
    )
    new_plan, recall_ids = apply_edit_plan(base_plan, edit)
    assert recall_ids == ["m1_rep"]
    assert len(new_plan.groups[0].metric_tree.children) == 2
    # m2 is untouched
    assert new_plan.groups[0].metric_tree.children[1].condition_id == "m2"
    # m1 replaced
    assert new_plan.groups[0].metric_tree.children[0].condition_id == "m1_rep"
    assert new_plan.groups[0].metric_tree.children[0].value_hint == 20.0


def test_apply_edit_plan_add_condition(base_plan):
    edit = EditPlan(
        operations=[
            EditOperation(
                action="ADD",
                groupId="g1",
                condition=MetricLeaf(conditionId="m3", mention="近1年收益大于10%", operatorHint=">", valueHint=10.0, unitHint="%"),
            )
        ]
    )
    new_plan, recall_ids = apply_edit_plan(base_plan, edit)
    assert recall_ids == ["m3"]
    assert len(new_plan.groups[0].metric_tree.children) == 3
    assert new_plan.groups[0].metric_tree.children[2].condition_id == "m3"


def test_apply_edit_plan_remove_condition(base_plan):
    edit = EditPlan(
        operations=[
            EditOperation(
                action="REMOVE",
                targetConditionId="m1",
            )
        ]
    )
    new_plan, recall_ids = apply_edit_plan(base_plan, edit)
    assert recall_ids == []
    assert len(new_plan.groups[0].metric_tree.children) == 1
    assert new_plan.groups[0].metric_tree.children[0].condition_id == "m2"


def test_apply_edit_plan_reset(base_plan):
    edit = EditPlan(
        operations=[
            EditOperation(action="RESET")
        ]
    )
    new_plan, recall_ids = apply_edit_plan(base_plan, edit)
    assert recall_ids == []
    assert len(new_plan.groups) == 0
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/unit/test_edit_plan.py -v`
Expected: FAIL (ModuleNotFoundError: No module named 'app.models.edit_plan')

- [ ] **Step 3: Implement `EditPlan` models and `apply_edit_plan`**

Create `fund-screening-service/app/models/edit_plan.py`:
```python
from __future__ import annotations

from typing import Any, Literal
from pydantic import BaseModel, ConfigDict, Field

from app.models.semantic_plan import MetricLeaf, ScopeLeaf, SortMention

EditAction = Literal["ADD", "REPLACE", "REMOVE", "CHANGE_OPERATOR", "RESET"]


class EditOperation(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    action: EditAction
    target_condition_id: str | None = Field(default=None, alias="targetConditionId")
    group_id: str | None = Field(default=None, alias="groupId")
    condition: MetricLeaf | ScopeLeaf | None = None
    operator: Literal["AND", "OR", "NOT"] | None = None


class EditPlan(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    intent: Literal["REBUILD"] = "REBUILD"
    operations: list[EditOperation] = Field(default_factory=list)
    sort_mentions: list[SortMention] | None = Field(default=None, alias="sortMentions")
    limit: int | None = None
```

In `fund-screening-service/app/pipeline/editor.py`, add `summarize_plan_for_edit` and `apply_edit_plan`:
```python
from app.models.semantic_plan import SemanticPlan, PlanGroup, MetricTree, ScopeTree, MetricLeaf, ScopeLeaf
from app.models.edit_plan import EditPlan, EditOperation


def summarize_plan_for_edit(plan: SemanticPlan) -> dict[str, Any]:
    conditions = []
    for g in plan.groups:
        for c in _gather_leaves(g.scope_tree):
            conditions.append({
                "conditionId": c.condition_id,
                "domain": "scope",
                "mention": c.mention,
                "groupId": g.group_id,
            })
        for c in _gather_leaves(g.metric_tree):
            conditions.append({
                "conditionId": c.condition_id,
                "domain": c.domain,
                "mention": c.mention,
                "operatorHint": c.operator_hint,
                "valueHint": c.value_hint,
                "unitHint": c.unit_hint,
                "groupId": g.group_id,
            })
    return {
        "groups": len(plan.groups),
        "conditions": conditions,
        "sortMentions": [s.model_dump(by_alias=True) for s in plan.sort_mentions],
        "limit": plan.limit,
    }


def _gather_leaves(tree: Any) -> list[Any]:
    if tree is None:
        return []
    leaves = []
    for child in getattr(tree, "children", []):
        if hasattr(child, "mention") and hasattr(child, "condition_id"):
            leaves.append(child)
        elif hasattr(child, "children"):
            leaves.extend(_gather_leaves(child))
    return leaves


def apply_edit_plan(previous_plan: SemanticPlan, edit_plan: EditPlan) -> tuple[SemanticPlan, list[str]]:
    recall_ids: list[str] = []

    # Check for RESET
    if any(op.action == "RESET" for op in edit_plan.operations):
        new_plan = SemanticPlan(
            intent="REBUILD",
            groups=[],
            sort_mentions=edit_plan.sort_mentions or [],
            limit=edit_plan.limit or 20,
        )
        return new_plan, recall_ids

    # Deep copy groups
    new_groups = [g.model_copy(deep=True) for g in previous_plan.groups]
    if not new_groups:
        new_groups = [PlanGroup(groupId="g1", scopeTree=ScopeTree(), metricTree=MetricTree())]

    for op in edit_plan.operations:
        if op.action == "ADD" and op.condition is not None:
            recall_ids.append(op.condition.condition_id)
            target_gid = op.group_id or new_groups[0].group_id
            target_g = next((g for g in new_groups if g.group_id == target_gid), new_groups[0])
            if isinstance(op.condition, ScopeLeaf):
                target_g.scope_tree.children.append(op.condition)
            else:
                target_g.metric_tree.children.append(op.condition)

        elif op.action == "REPLACE" and op.condition is not None and op.target_condition_id:
            recall_ids.append(op.condition.condition_id)
            target_cid = op.target_condition_id
            for g in new_groups:
                g.scope_tree = _replace_in_tree(g.scope_tree, target_cid, op.condition)
                g.metric_tree = _replace_in_tree(g.metric_tree, target_cid, op.condition)

        elif op.action == "REMOVE" and op.target_condition_id:
            target_cid = op.target_condition_id
            for g in new_groups:
                g.scope_tree = _remove_from_tree(g.scope_tree, target_cid)
                g.metric_tree = _remove_from_tree(g.metric_tree, target_cid)

        elif op.action == "CHANGE_OPERATOR" and op.operator:
            if op.group_id:
                for g in new_groups:
                    if g.group_id == op.group_id:
                        g.metric_tree.operator = op.operator
            else:
                for g in new_groups:
                    g.metric_tree.operator = op.operator

    new_sort = edit_plan.sort_mentions if edit_plan.sort_mentions is not None else previous_plan.sort_mentions
    new_limit = edit_plan.limit if edit_plan.limit is not None else previous_plan.limit

    return SemanticPlan(
        intent="REBUILD",
        group_operator=previous_plan.group_operator,
        groups=new_groups,
        sort_mentions=new_sort,
        limit=new_limit,
    ), recall_ids


def _replace_in_tree(tree: Any, target_cid: str, replacement: Any) -> Any:
    if tree is None:
        return None
    new_children = []
    for child in getattr(tree, "children", []):
        if getattr(child, "condition_id", None) == target_cid:
            new_children.append(replacement)
        elif hasattr(child, "children"):
            new_children.append(_replace_in_tree(child, target_cid, replacement))
        else:
            new_children.append(child)
    return tree.model_copy(update={"children": new_children})


def _remove_from_tree(tree: Any, target_cid: str) -> Any:
    if tree is None:
        return None
    new_children = []
    for child in getattr(tree, "children", []):
        if getattr(child, "condition_id", None) == target_cid:
            continue
        elif hasattr(child, "children"):
            sub = _remove_from_tree(child, target_cid)
            if sub.children:
                new_children.append(sub)
        else:
            new_children.append(child)
    return tree.model_copy(update={"children": new_children})
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest tests/unit/test_edit_plan.py -v`
Expected: PASS

- [ ] **Step 5: Verify no regression across unit tests**

Run: `pytest tests/unit -v`
Expected: ALL PASS

---

### Task 2: Plan LLM Multi-Round Generation (`generate_edit_plan`)

**Files:**
- Modify: `fund-screening-service/app/retrieval/llm.py`
- Test: `fund-screening-service/tests/unit/test_edit_plan_llm.py`

**Interfaces:**
- Consumes: `EditPlan` from `app.models.edit_plan`, `summarize_plan_for_edit` from `app.pipeline.editor`.
- Produces: `OpenAiSemanticLlm.generate_edit_plan(query: str, previous_summary: dict[str, Any]) -> EditPlan`

- [ ] **Step 1: Write failing unit test for `generate_edit_plan`**

```python
# fund-screening-service/tests/unit/test_edit_plan_llm.py
import pytest
from app.models.edit_plan import EditPlan, EditOperation
from app.models.semantic_plan import MetricLeaf
from app.retrieval.llm import OpenAiSemanticLlm


@pytest.mark.asyncio
async def test_generate_edit_plan_mock():
    llm = OpenAiSemanticLlm()
    class MockRunnable:
        async def ainvoke(self, args):
            return EditPlan(
                operations=[
                    EditOperation(
                        action="REPLACE",
                        targetConditionId="m1",
                        condition=MetricLeaf(conditionId="m1_new", mention="规模大于20亿", operatorHint=">", valueHint=20.0, unitHint="亿"),
                    ),
                    EditOperation(
                        action="ADD",
                        groupId="g1",
                        condition=MetricLeaf(conditionId="m3", mention="收益大于15%", operatorHint=">", valueHint=15.0, unitHint="%"),
                    ),
                ]
            )

    llm.edit_plan_runnable = MockRunnable()
    edit_plan = await llm.generate_edit_plan("把规模改成20亿，再加收益大于15%", {"conditions": [{"conditionId": "m1", "mention": "规模大于10亿"}]})
    assert len(edit_plan.operations) == 2
    assert edit_plan.operations[0].action == "REPLACE"
    assert edit_plan.operations[0].target_condition_id == "m1"
    assert edit_plan.operations[1].action == "ADD"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/unit/test_edit_plan_llm.py -v`
Expected: FAIL (AttributeError: 'OpenAiSemanticLlm' object has no attribute 'generate_edit_plan')

- [ ] **Step 3: Implement `generate_edit_plan` in `OpenAiSemanticLlm`**

In `fund-screening-service/app/retrieval/llm.py`:
Add `EDIT_PLAN_SYSTEM_PROMPT`:
```python
EDIT_PLAN_SYSTEM_PROMPT = """你只负责根据上一轮筛选条件摘要 previousPlanSummary 和用户新输入 query，输出增量编辑计划 EditPlan。
严禁生成最终 DSL 或业务 ID。只能输出结构化编辑操作：
- ADD: 增加新叶子条件 (提供 conditionId, mention, domain, operatorHint, valueHint, unitHint)
- REPLACE: 替换现有条件 (必须指定 targetConditionId, 以及新 condition)
- REMOVE: 删除条件 (必须指定 targetConditionId)
- CHANGE_OPERATOR: 修改逻辑运算符
- RESET: 清空并重新构建
未在 operations 中出现的条件将严格保持原样。
"""
```

Add property `edit_plan_runnable` and method `generate_edit_plan` to `OpenAiSemanticLlm`:
```python
    @property
    def edit_plan_runnable(self):
        if not hasattr(self, "_edit_plan_runnable"):
            if self.chat_model is None:
                self._edit_plan_runnable = None
            else:
                edit_plan_prompt = ChatPromptTemplate.from_messages([
                    SystemMessage(content=EDIT_PLAN_SYSTEM_PROMPT),
                    HumanMessagePromptTemplate.from_template("{context}"),
                ])
                try:
                    self._edit_plan_runnable = edit_plan_prompt | self.chat_model.with_structured_output(EditPlan, method="json_schema")
                except (TypeError, NotImplementedError, AttributeError):
                    try:
                        self._edit_plan_runnable = edit_plan_prompt | self.chat_model.with_structured_output(EditPlan)
                    except (TypeError, NotImplementedError, AttributeError):
                        self._edit_plan_runnable = None
        return self._edit_plan_runnable

    @edit_plan_runnable.setter
    def edit_plan_runnable(self, value):
        self._edit_plan_runnable = value

    async def generate_edit_plan(self, query: str, previous_summary: dict[str, Any]) -> EditPlan:
        context = json.dumps(
            {"query": query, "previousPlanSummary": previous_summary},
            ensure_ascii=False,
        )
        try:
            if self.edit_plan_runnable is not None:
                plan: EditPlan = await self.edit_plan_runnable.ainvoke({"context": context})
            else:
                plan = await self._complete_structured(EDIT_PLAN_SYSTEM_PROMPT, context, EditPlan)
        except Exception:
            plan = await self._complete_structured(EDIT_PLAN_SYSTEM_PROMPT, context, EditPlan)
        return plan
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest tests/unit/test_edit_plan_llm.py -v`
Expected: PASS

---

### Task 3: Graph Nodes Integration for Unified Pipeline & Condition-level Incremental Recall

**Files:**
- Modify: `fund-screening-service/app/graph/state.py`
- Modify: `fund-screening-service/app/pipeline/analysis.py`
- Modify: `fund-screening-service/app/graph/nodes.py`
- Test: `fund-screening-service/tests/unit/test_graph_unified_nodes.py`

**Interfaces:**
- Consumes: `EditPlan`, `apply_edit_plan`, `summarize_plan_for_edit`, `SemanticPlan`.
- Produces:
  - `ScreeningGraphNodes.semantic_plan`: executes Plan LLM (generating `SemanticPlan` for `CREATE`, or `EditPlan` for `REBUILD_COMPLEX` and patching previous plan).
  - `ScreeningGraphNodes.recall_by_condition`: only queries candidates for `conditions_to_recall`, merging with existing contract pool.
  - Checkpoints updated state: `semantic_plan`, `edit_plan`, `conditions_to_recall`, `by_condition`, `candidate_contract_index`.
  - Removes all dead legacy `RecallPlan` fallback logic.

- [ ] **Step 1: Write failing unit test for unified graph nodes with incremental edit**

```python
# fund-screening-service/tests/unit/test_graph_unified_nodes.py
import pytest
from app.graph.nodes import ScreeningGraphNodes
from app.graph.state import empty_state
from app.models.candidate import CandidateContract
from app.models.edit_plan import EditPlan, EditOperation
from app.models.semantic_plan import (
    SemanticPlan,
    PlanGroup,
    MetricTree,
    MetricLeaf,
    AdjudicationResult,
    ConditionBinding,
)


class MockUnifiedAnalysis:
    def __init__(self):
        self.plan_called = 0
        self.edit_plan_called = 0
        self.recall_conditions_called = []

    async def generate_semantic_plan(self, query: str, previous_dsl: dict | None = None):
        self.plan_called += 1
        return SemanticPlan(
            intent="CREATE",
            groups=[
                PlanGroup(
                    groupId="g1",
                    metricTree=MetricTree(
                        operator="AND",
                        children=[
                            MetricLeaf(conditionId="m1", mention="规模大于10亿"),
                            MetricLeaf(conditionId="m2", mention="成立大于3年"),
                        ],
                    ),
                )
            ],
        )

    async def generate_edit_plan(self, query: str, previous_summary: dict):
        self.edit_plan_called += 1
        return EditPlan(
            operations=[
                EditOperation(
                    action="ADD",
                    groupId="g1",
                    condition=MetricLeaf(conditionId="m3", mention="收益大于15%"),
                )
            ]
        )

    async def recall_by_condition(self, plan: SemanticPlan, target_condition_ids: list[str] | None = None):
        self.recall_conditions_called.append(target_condition_ids)
        c3 = CandidateContract(
            domain="metric",
            key="f_return_1y",
            name="近1年收益率",
            supported_operators=(">", ">="),
        )
        by_condition = {
            "m3": {"mention": "收益大于15%", "domain": "metric", "candidates": [c3]}
        }
        contract_index = {"f_return_1y": c3}
        return by_condition, contract_index

    async def adjudicate_candidates(self, plan, by_condition, route="simple"):
        return AdjudicationResult(
            conditionBindings=[
                ConditionBinding(conditionId="m3", candidateKey="f_return_1y", operator=">", value=15.0, unit="%")
            ]
        )


@pytest.mark.asyncio
async def test_rebuild_complex_invokes_edit_plan_and_partial_recall():
    analysis = MockUnifiedAnalysis()
    nodes = ScreeningGraphNodes(analysis=analysis)

    prev_plan = SemanticPlan(
        intent="CREATE",
        groups=[
            PlanGroup(
                groupId="g1",
                metricTree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(conditionId="m1", mention="规模大于10亿"),
                        MetricLeaf(conditionId="m2", mention="成立大于3年"),
                    ],
                ),
            )
        ],
    )
    state = empty_state("再加一个收益大于15%", previous_dsl={"version": "v1"})
    state["intent"] = "REBUILD"
    state["edit_complexity"] = "COMPLEX"
    state["semantic_plan"] = prev_plan.model_dump(by_alias=True)
    state["candidate_contract_index"] = {
        "f_scale": {"key": "f_scale", "domain": "metric", "name": "规模", "supported_operators": [">"]}
    }

    # 1. semantic_plan node in REBUILD_COMPLEX
    sem_res = await nodes.semantic_plan(state)
    state = {**state, **sem_res}
    assert analysis.edit_plan_called == 1
    assert state.get("conditions_to_recall") == ["m3"]
    assert len(state["semantic_plan"]["groups"][0]["metricTree"]["children"]) == 3

    # 2. recall_by_condition node
    rec_res = await nodes.recall_by_condition(state)
    state = {**state, **rec_res}
    assert analysis.recall_conditions_called == [["m3"]]
    assert "f_scale" in state["candidate_contract_index"]
    assert "f_return_1y" in state["candidate_contract_index"]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/unit/test_graph_unified_nodes.py -v`
Expected: FAIL

- [ ] **Step 3: Implement updates in `FundScreeningState`, `RecallByConditionService`, and `ScreeningGraphNodes`**

In `fund-screening-service/app/graph/state.py`:
Add `edit_plan: dict[str, Any] | None` and `conditions_to_recall: list[str] | None` to `FundScreeningState`.

In `fund-screening-service/app/pipeline/analysis.py`:
Update `RecallByConditionService.recall(self, plan: Any, target_condition_ids: list[str] | None = None)` to filter leaves to recall if `target_condition_ids` is provided.

In `fund-screening-service/app/graph/nodes.py`:
1. In `semantic_plan(self, state)`:
   - If `state.get("intent") == "REBUILD"` and state.get("edit_complexity") == "COMPLEX":
     - Check if `semantic_plan` exists in state; if not, reconstruct a baseline `SemanticPlan` from `state.get("previous_dsl")` or empty plan.
     - Call `self._analysis.generate_edit_plan(query, summarize_plan_for_edit(prev_plan))`.
     - Call `apply_edit_plan(prev_plan, edit_plan)`.
     - Return updated `semantic_plan`, `edit_plan`, `conditions_to_recall`.
   - Else (`CREATE` or no edit):
     - Call `self._analysis.generate_semantic_plan(query)`.
     - Return `semantic_plan`.
2. In `recall_by_condition(self, state)`:
   - Retrieve `conditions_to_recall = state.get("conditions_to_recall")`.
   - Call `self._analysis.recall_by_condition(plan, target_condition_ids=conditions_to_recall)`.
   - Merge `by_condition` and `candidate_contract_index` with existing in `state`.
3. In `_run_adjudication(self, state, route)`:
   - Call `self._analysis.adjudicate_candidates(plan, by_condition, route=route)`.
   - Merge bindings into `adjudication_result`.
4. Remove legacy fallback branches that referred to `RecallPlan` or `state.get("recall_plan")`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest tests/unit/test_graph_unified_nodes.py -v`
Expected: PASS

- [ ] **Step 5: Run existing graph tests to verify no regressions**

Run: `pytest tests/unit/test_graph_*.py -v`
Expected: ALL PASS

---

### Task 4: Production Runtime & Orchestrator Migration

**Files:**
- Modify: `fund-screening-service/app/runtime.py`
- Modify: `fund-screening-service/app/main.py`
- Modify: `fund-screening-service/app/pipeline/orchestrator.py`
- Test: `fund-screening-service/tests/unit/test_unified_runtime.py`

**Interfaces:**
- Consumes: `OpenAiSemanticLlm`, `RecallByConditionService`, `ScreeningGraphNodes`, `CommandBuilder`, `IntentFunnel`.
- Produces: `build_production_compiler(settings: Settings | None = None) -> LangGraphCommandCompiler` which completely injects the unified semantic pipeline (zero legacy fallback).

- [ ] **Step 1: Write failing test verifying production runtime uses unified pipeline**

```python
# fund-screening-service/tests/unit/test_unified_runtime.py
from pathlib import Path
from app.catalog.loader import Catalog
from app.config import Settings
from app.runtime import build_production_compiler


def test_production_compiler_wires_unified_pipeline():
    repo_root = Path(__file__).resolve().parents[2]
    metrics_path = repo_root.parent / "docs" / "temp" / "metrics.json"
    sectors_path = repo_root / "tests" / "fixtures" / "sectors-small.json"
    entities_path = repo_root / "tests" / "fixtures" / "entities-small.json"

    settings = Settings(
        pipeline_mode="rag",
        metrics_catalog_path=str(metrics_path),
        sectors_catalog_path=str(sectors_path),
        entity_catalog_path=str(entities_path),
        llm_api_key="test-key",
        embedding_api_key="test-key",
    )
    compiler = build_production_compiler(settings)
    assert compiler is not None
    assert hasattr(compiler, "graph")
```

- [ ] **Step 2: Run test to verify it fails if runtime still relies on legacy analysis**

Run: `pytest tests/unit/test_unified_runtime.py -v`

- [ ] **Step 3: Update `app/runtime.py` and `app/main.py`**

In `fund-screening-service/app/runtime.py`:
```python
class UnifiedAnalysisFacade:
    """门面适配器：将 OpenAiSemanticLlm 与 RecallByConditionService 统一对接到 ScreeningGraphNodes。"""

    def __init__(self, llm: OpenAiSemanticLlm, recall_service: RecallByConditionService) -> None:
        self._llm = llm
        self._recall = recall_service

    async def generate_semantic_plan(self, query: str, previous_dsl: dict | None = None):
        return await self._llm.generate_semantic_plan(query, previous_dsl)

    async def generate_edit_plan(self, query: str, previous_summary: dict):
        return await self._llm.generate_edit_plan(query, previous_summary)

    async def recall_by_condition(self, plan: Any, target_condition_ids: list[str] | None = None):
        return await self._recall.recall(plan, target_condition_ids=target_condition_ids)

    async def adjudicate_candidates(self, plan: Any, by_condition: dict[str, Any], route: str = "simple"):
        return await self._llm.adjudicate_candidates(plan, by_condition, route=route)


def build_production_compiler(settings: Settings | None = None) -> LangGraphCommandCompiler:
    settings = settings or Settings()
    if not all((settings.metrics_catalog_path, settings.sectors_catalog_path, settings.entity_catalog_path)):
        raise RuntimeError("metrics, sectors and entity catalog paths are required in rag mode")
    catalog = Catalog.from_files(
        Path(settings.metrics_catalog_path),
        Path(settings.sectors_catalog_path),
        Path(settings.entity_catalog_path),
    )
    llm_http = httpx.AsyncClient(base_url=settings.llm_base_url, headers={"Authorization": f"Bearer {settings.llm_api_key}"}, timeout=60, trust_env=False)
    embedding_http = httpx.AsyncClient(base_url=settings.embedding_base_url, headers={"Authorization": f"Bearer {settings.embedding_api_key}"}, timeout=30, trust_env=False)
    llm = OpenAiSemanticLlm(
        llm_http,
        settings.llm_model,
        settings.llm_reasoning_effort,
        base_url=settings.llm_base_url,
        api_key=settings.llm_api_key,
    )
    embedding = OpenAiEmbeddingClient(embedding_http, settings.embedding_model)
    vector = PgVectorSearchClient(LazyAsyncpgPool(settings.database_url), embedding)

    recall_service = RecallByConditionService(
        metric_retriever=PgVectorMetricRetriever(vector),
        scope_retriever=PgVectorScopeRetriever(vector),
        entity_retriever=LexicalEntityRetriever(catalog.entities),
    )
    analysis = UnifiedAnalysisFacade(llm, recall_service)

    nodes = ScreeningGraphNodes(
        analysis=analysis,
        builder=CommandBuilder(default_sector_id=settings.default_sector_id),
        candidates_catalog=catalog.metrics,
        intent_funnel=IntentFunnel(llm.classify_context_intent, llm.classify_complex_intent),
    )
    checkpointer = build_checkpointer(settings.redis_url)
    graph = build_screening_graph(nodes, checkpointer=checkpointer)
    return LangGraphCommandCompiler(graph)
```

In `fund-screening-service/app/main.py`:
Update `_build_default_graph()` to use `UnifiedAnalysisFacade` and `RecallByConditionService` over mock catalogs so default mode behaves identically to production mode.

In `fund-screening-service/app/pipeline/orchestrator.py`:
Ensure `run(...)` strictly delegates to `self._graph` and emits all events consistently.

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest tests/unit/test_unified_runtime.py -v`
Expected: PASS

---

### Task 5: End-to-End Multi-Round Incremental Edit & Evaluation Observability

**Files:**
- Create: `fund-screening-service/tests/integration/test_unified_pipeline_acceptance.py`
- Test: `fund-screening-service/tests/integration/test_unified_pipeline_acceptance.py`

**Interfaces:**
- Tests full lifecycle of the unified pipeline across multi-round interactions:
  - Round 1 (CREATE): Query enters Intent Funnel -> Plan LLM -> Condition Recall -> Full/Simple Router -> Adjudication -> DSL Builder -> Validated DSL. Checkpoint saved.
  - Round 2 (REBUILD_COMPLEX): "把规模改成20亿，再加收益大于10%" -> Intent Funnel -> Plan LLM (EditPlan) -> Patches plan -> Condition Recall (ONLY new condition) -> Adjudication (binds new condition) -> Untouched conditions preserved -> Validated DSL.
  - Round 3 (REBUILD_SIMPLE): "改成前50只" -> Intent Funnel -> Strict Rule Bypass (0 LLM) -> Deterministic AST patch -> Validated DSL.
  - Evaluation: verify trace events, checkpoints, Top-K recall, and zero hallucinated metric keys.

- [ ] **Step 1: Write integration test for the unified pipeline multi-round lifecycle**

```python
# fund-screening-service/tests/integration/test_unified_pipeline_acceptance.py
import pytest
from langgraph.checkpoint.memory import MemorySaver
from app.graph.workflow import build_screening_graph
from app.graph.nodes import ScreeningGraphNodes
from app.models.candidate import CandidateContract
from app.models.dsl import FundScreeningDsl
from app.models.edit_plan import EditPlan, EditOperation
from app.models.semantic_plan import (
    SemanticPlan,
    PlanGroup,
    MetricTree,
    MetricLeaf,
    AdjudicationResult,
    ConditionBinding,
)
from app.pipeline.orchestrator import ScreeningOrchestrator


class MockPipelineLlmService:
    def __init__(self):
        self.plan_calls = 0
        self.edit_calls = 0
        self.adjudicate_calls = 0

    async def generate_semantic_plan(self, query: str, previous_dsl=None):
        self.plan_calls += 1
        return SemanticPlan(
            intent="CREATE",
            groups=[
                PlanGroup(
                    groupId="g1",
                    metricTree=MetricTree(
                        operator="AND",
                        children=[
                            MetricLeaf(conditionId="m1", mention="规模大于10亿", operatorHint=">", valueHint=10.0, unitHint="亿"),
                            MetricLeaf(conditionId="m2", mention="成立年限大于3年", operatorHint=">", valueHint=3.0, unitHint="年"),
                        ],
                    ),
                )
            ],
            limit=20,
        )

    async def generate_edit_plan(self, query: str, previous_summary: dict):
        self.edit_calls += 1
        return EditPlan(
            operations=[
                EditOperation(
                    action="REPLACE",
                    targetConditionId="m1",
                    condition=MetricLeaf(conditionId="m1_rep", mention="规模大于20亿", operatorHint=">", valueHint=20.0, unitHint="亿"),
                ),
                EditOperation(
                    action="ADD",
                    groupId="g1",
                    condition=MetricLeaf(conditionId="m3", mention="收益大于10%", operatorHint=">", valueHint=10.0, unitHint="%"),
                ),
            ]
        )

    async def recall_by_condition(self, plan: SemanticPlan, target_condition_ids: list[str] | None = None):
        c_scale = CandidateContract(domain="metric", key="f_info_fundscale", name="基金规模", supported_operators=(">", ">="))
        c_year = CandidateContract(domain="metric", key="f_info_existingyear", name="成立年限", supported_operators=(">", ">="))
        c_ret = CandidateContract(domain="metric", key="f_return_1y", name="近1年收益率", supported_operators=(">", ">="))
        
        all_map = {"m1": c_scale, "m1_rep": c_scale, "m2": c_year, "m3": c_ret}
        by_cond = {}
        contract_index = {}
        for cid, cand in all_map.items():
            if target_condition_ids is None or cid in target_condition_ids:
                by_cond[cid] = {"mention": cand.name, "domain": "metric", "candidates": [cand]}
                contract_index[cand.key] = cand
        return by_cond, contract_index

    async def adjudicate_candidates(self, plan: SemanticPlan, by_condition: dict, route: str = "simple"):
        self.adjudicate_calls += 1
        bindings = []
        if "m1" in by_condition:
            bindings.append(ConditionBinding(conditionId="m1", candidateKey="f_info_fundscale", operator=">", value=10.0, unit="亿"))
        if "m1_rep" in by_condition:
            bindings.append(ConditionBinding(conditionId="m1_rep", candidateKey="f_info_fundscale", operator=">", value=20.0, unit="亿"))
        if "m2" in by_condition:
            bindings.append(ConditionBinding(conditionId="m2", candidateKey="f_info_existingyear", operator=">", value=3.0, unit="年"))
        if "m3" in by_condition:
            bindings.append(ConditionBinding(conditionId="m3", candidateKey="f_return_1y", operator=">", value=10.0, unit="%"))
        return AdjudicationResult(conditionBindings=bindings)


@pytest.mark.asyncio
async def test_unified_pipeline_multi_round_lifecycle():
    service = MockPipelineLlmService()
    nodes = ScreeningGraphNodes(analysis=service)
    checkpointer = MemorySaver()
    graph = build_screening_graph(nodes, checkpointer=checkpointer)
    orchestrator = ScreeningOrchestrator(graph=graph)

    conv_id = "test-conv-unified-1"

    # --- Round 1: CREATE ---
    dsl_r1 = await orchestrator.run("规模大于10亿且成立超过3年", conversation_id=conv_id)
    assert isinstance(dsl_r1, FundScreeningDsl)
    assert service.plan_calls == 1
    assert service.edit_calls == 0
    assert service.adjudicate_calls == 1
    assert len(dsl_r1.children[0].metric.children) == 2
    r1_dict = dsl_r1.model_dump(by_alias=True, exclude_none=True)

    # --- Round 2: REBUILD_COMPLEX ---
    dsl_r2 = await orchestrator.run(
        "把规模改成20亿，再加一个收益大于10%",
        previous_dsl=r1_dict,
        conversation_id=conv_id,
    )
    assert isinstance(dsl_r2, FundScreeningDsl)
    assert service.plan_calls == 1
    assert service.edit_calls == 1
    assert service.adjudicate_calls == 2
    pred_metrics = {p.metric: p.value for p in dsl_r2.children[0].metric.children}
    assert pred_metrics["f_info_existingyear"] == 3.0
    assert pred_metrics["f_info_fundscale"] == 20.0
    assert pred_metrics["f_return_1y"] == 10.0
    r2_dict = dsl_r2.model_dump(by_alias=True, exclude_none=True)

    # --- Round 3: REBUILD_SIMPLE (Strict Rule Bypass: 0 LLM calls) ---
    dsl_r3 = await orchestrator.run(
        "改成前50只",
        previous_dsl=r2_dict,
        conversation_id=conv_id,
    )
    assert isinstance(dsl_r3, FundScreeningDsl)
    assert service.plan_calls == 1  # 0 new LLM call
    assert service.edit_calls == 1  # 0 new LLM call
    assert service.adjudicate_calls == 2  # 0 new LLM call
    assert dsl_r3.limit == 50
    assert len(dsl_r3.children[0].metric.children) == 3
```

- [ ] **Step 2: Run test to verify it executes and passes**

Run: `pytest tests/integration/test_unified_pipeline_acceptance.py -v`
Expected: PASS

---

### Task 6: Full Regression Verification

**Files:**
- Run: all tests under `fund-screening-service/tests`

- [ ] **Step 1: Execute full test suite**

Run:
```powershell
& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit tests/integration tests/api -v
```
Expected: All 160+ unit, integration, and API tests PASS.

- [ ] **Step 2: Verify git status is clean of unwanted files and matches repo discipline**

Run: `git status`
Expected: Only relevant files modified or created.
