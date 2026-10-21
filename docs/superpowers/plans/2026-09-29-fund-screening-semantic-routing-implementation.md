# 基金筛选语义编译与三级分流实施计划 (Fund Screening Semantic Routing Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将自然语言基金筛选请求重构为三级确定性语义编译系统（Strict Direct 探针、Semantic Plan 逻辑树规划、Condition 级三路召回、Simple/Full 路由、候选受限叶子裁决、AST 确定性组装与有界闭环修复校验），完全落地 `2026-09-29-fund-screening-semantic-routing-design.md`。

**Architecture:** 严格解耦逻辑树结构与候选叶子绑定：LLM #1 负责生成包含 Group 与递归逻辑树的结构化 Semantic Plan（严禁产生指标/板块/实体 ID）；召回器依据 Condition 领域分发并行检索并生成 `byCondition` 候选约束；确定性路由器根据复杂度评估分流至 Simple/Full；LLM #2 仅对逻辑树叶子节点输出 Condition Bindings（严禁重建或修改逻辑树结构）；CommandBuilder 确定性组装递归 DSL 并由 Validator 闭环修复。

**Tech Stack:** Python 3.11+、FastAPI、LangChain、LangGraph、Pydantic v2、PostgreSQL/PGVector、pytest

**Spec:** `docs/superpowers/specs/2026-09-29-fund-screening-semantic-routing-design.md`

## Global Constraints

- **逻辑树与候选绑定绝对解耦：** Semantic Plan 是业务分组和递归逻辑树的唯一来源；候选裁决 LLM #2 只输出叶子节点的 `conditionBindings`，严禁重新解释或改写逻辑树。
- **调用次数上限契约：** `Direct` 路径严格 0 次生成式 LLM；`Simple` 路径至多调用一次 Semantic Plan LLM #1 与一次轻量候选裁决 LLM #2；`Full` 路径至多调用一次 Semantic Plan LLM #1 与一次完整候选裁决 LLM #2；禁止在修复节点调用 LLM。
- **候选受限与零幻觉：** LLM #1 禁止输出任何指标 ID、板块 ID 或实体 ID；LLM #2 的 `candidateKey` 必须严格来自该 `conditionId` 的 `byCondition` 候选列表，严禁自由捏造。
- **实体多角色绑定契约：** 实体契约（CandidateContract）支持多种 `BindingRole`（例如指数既可绑定为指标条件 `f_info_trackindex`，也可绑定为参数 `indexCode`），由 LLM 选择 Role，程序根据契约完成 AST 挂载。
- **有界自愈与精准澄清：** Validator 仅执行不会改变投资意图的确定性修复（如负值转换、枚举规范、默认板块补全）；无法自动确定的歧义必须生成携带对应 `conditionId` 的结构化 `clarification`，避免盲目猜测。
- **测试与运行命令：** 所有测试通过 `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest` 在 `fund-screening-service` 目录下执行。

---

## File Structure

```text
fund-screening-service/
├── app/
│   ├── models/
│   │   ├── semantic_plan.py          # [Task 1] Semantic Plan AST、Condition Binding 与 Adjudication 契约模型
│   │   ├── candidate.py              # [Task 4] CandidateContract 扩展 bindings (支持多角色 BindingRole)
│   │   ├── dsl.py                    # 既有 DSL AST 模型 (MetricExpression / SectorExpression / FundScreeningDsl)
│   │   └── parameters.py             # 既有指标参数定义契约
│   ├── graph/
│   │   ├── direct_probe.py           # [Task 2] StrictDirectProbe 确定性免 LLM 直通探针
│   │   ├── router.py                 # [Task 5] Simple / Full 二次分流路由器 (基于 Plan 复杂度与歧义度)
│   │   ├── state.py                  # [Task 9] FundScreeningState 扩展 plan、by_condition_pool 等状态
│   │   ├── nodes.py                  # [Task 9] 各 Graph 阶段节点包装与 Trace 记录
│   │   ├── workflow.py               # [Task 9] 组装 StrictDirectProbe -> SemanticPlan -> Recall -> Router -> Adjudication 编译图
│   │   └── repair.py                 # 既有机械修复与 Clarification 分支判定
│   ├── pipeline/
│   │   ├── analysis.py               # [Task 4] Condition-level 并行三路召回 (byCondition 映射与 contractIndex 构建)
│   │   ├── builder.py                # [Task 7] AST 确定性 CommandBuilder (将 Bindings 挂回 Plan 递归逻辑树)
│   │   ├── validator.py              # [Task 8] 增强递归校验器，携带 conditionId 级别的澄清与负值口径转换
│   │   ├── editor.py                 # [Task 10] 多轮增量编辑 (简单 Patch vs 局部 Condition 差异召回重裁决)
│   │   └── orchestrator.py           # 统一流式事件编排与 DSL 收口交付
│   ├── retrieval/
│   │   └── llm.py                    # [Task 3, 6] LLM #1 (SemanticPlan) 与 LLM #2 (Simple / Full Adjudication)
│   ├── api/
│   │   └── commands.py               # [Task 11] HTTP / WebSocket API 路由
│   └── runtime.py                    # 生产依赖组装与 Graph 编译单例
└── tests/
    └── unit/
        ├── test_semantic_plan_models.py       # [Task 1]
        ├── test_direct_probe.py               # [Task 2]
        ├── test_semantic_plan_llm.py          # [Task 3]
        ├── test_condition_recall.py           # [Task 4]
        ├── test_semantic_router.py            # [Task 5]
        ├── test_candidate_adjudication.py     # [Task 6]
        ├── test_ast_command_builder.py        # [Task 7]
        ├── test_semantic_validator.py         # [Task 8]
        ├── test_graph_semantic_pipeline.py    # [Task 9]
        ├── test_multi_round_semantic_edit.py  # [Task 10]
        └── test_semantic_routing_acceptance.py # [Task 11: 覆盖 Spec 14 场景]
```

---

### Task 1: Semantic Plan & Condition Binding Pydantic Schemas

**Files:**
- Create: `app/models/semantic_plan.py`
- Test: `tests/unit/test_semantic_plan_models.py`

**Interfaces:**
- Consumes: None (Pure Pydantic models)
- Produces: `SemanticPlan`, `PlanGroup`, `ScopeTree`, `ScopeLeaf`, `MetricTree`, `MetricLeaf`, `SortMention`, `ConditionBinding`, `SortBinding`, `AdjudicationResult`

- [ ] **Step 1: Write the failing test**

```python
# tests/unit/test_semantic_plan_models.py
import pytest
from app.models.semantic_plan import (
    SemanticPlan,
    PlanGroup,
    ScopeTree,
    ScopeLeaf,
    MetricTree,
    MetricLeaf,
    SortMention,
    ConditionBinding,
    SortBinding,
    AdjudicationResult,
)


def test_semantic_plan_model_serialization_and_validation():
    raw_plan = {
        "intent": "CREATE",
        "groupOperator": "AND",
        "groups": [
            {
                "groupId": "g1",
                "scopeTree": {
                    "operator": "OR",
                    "children": [
                        {"conditionId": "s1", "mention": "主动权益基金", "domain": "scope"}
                    ],
                },
                "metricTree": {
                    "operator": "AND",
                    "children": [
                        {
                            "conditionId": "m1",
                            "mention": "成立超过5年",
                            "domain": "metric",
                            "operatorHint": ">",
                            "valueHint": 5,
                            "unitHint": "年",
                        }
                    ],
                },
            }
        ],
        "sortMentions": [{"mention": "近一年收益率", "direction": "DESC"}],
        "limit": 20,
    }
    plan = SemanticPlan.model_validate(raw_plan)
    assert plan.intent == "CREATE"
    assert plan.group_operator == "AND"
    assert len(plan.groups) == 1
    group = plan.groups[0]
    assert group.group_id == "g1"
    assert len(group.scope_tree.children) == 1
    assert group.scope_tree.children[0].mention == "主动权益基金"
    assert group.metric_tree.children[0].operator_hint == ">"
    assert group.metric_tree.children[0].value_hint == 5


def test_adjudication_result_model_validation():
    raw_adj = {
        "conditionBindings": [
            {
                "conditionId": "m1",
                "candidateKey": "f_info_existingyear",
                "bindingRole": None,
                "operator": ">",
                "value": 5,
                "unit": "年",
                "params": {"endDate": "LATEST_TRADE_DATE"},
            }
        ],
        "sortBindings": [
            {"mention": "近一年收益率", "candidateKey": "f_nav_return_1y", "direction": "DESC"}
        ],
        "clarifications": [],
    }
    result = AdjudicationResult.model_validate(raw_adj)
    assert len(result.condition_bindings) == 1
    binding = result.condition_bindings[0]
    assert binding.condition_id == "m1"
    assert binding.candidate_key == "f_info_existingyear"
    assert binding.operator == ">"
    assert binding.value == 5
    assert binding.params == {"endDate": "LATEST_TRADE_DATE"}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_semantic_plan_models.py`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.models.semantic_plan'`

- [ ] **Step 3: Write minimal implementation**

```python
# app/models/semantic_plan.py
from __future__ import annotations

from typing import Any, Literal
from pydantic import BaseModel, ConfigDict, Field


class ScopeLeaf(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    condition_id: str = Field(alias="conditionId")
    mention: str
    domain: Literal["scope"] = "scope"


class ScopeTree(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    operator: Literal["AND", "OR", "NOT"] = "OR"
    children: list[ScopeTree | ScopeLeaf] = Field(default_factory=list)


class MetricLeaf(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    condition_id: str = Field(alias="conditionId")
    mention: str
    domain: Literal["metric", "entity"] = "metric"
    operator_hint: Literal[">", ">=", "<", "<=", "=", "!=", ""] | None = Field(default=None, alias="operatorHint")
    value_hint: Any | None = Field(default=None, alias="valueHint")
    unit_hint: str | None = Field(default=None, alias="unitHint")


class MetricTree(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    operator: Literal["AND", "OR", "NOT"] = "AND"
    children: list[MetricTree | MetricLeaf] = Field(default_factory=list)


class SortMention(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    mention: str
    direction: Literal["ASC", "DESC"] = "DESC"


class PlanGroup(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    group_id: str = Field(alias="groupId")
    scope_tree: ScopeTree = Field(default_factory=ScopeTree, alias="scopeTree")
    metric_tree: MetricTree = Field(default_factory=MetricTree, alias="metricTree")


class SemanticPlan(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    intent: Literal["CREATE", "EDIT", "CLARIFY"] = "CREATE"
    group_operator: Literal["AND", "OR"] = Field(default="AND", alias="groupOperator")
    groups: list[PlanGroup] = Field(default_factory=list)
    sort_mentions: list[SortMention] = Field(default_factory=list, alias="sortMentions")
    limit: int = 20


class ConditionBinding(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    condition_id: str = Field(alias="conditionId")
    candidate_key: str = Field(alias="candidateKey")
    binding_role: str | None = Field(default=None, alias="bindingRole")
    operator: Literal[">", ">=", "<", "<=", "=", "!="] = "="
    value: Any = None
    unit: str | None = None
    params: dict[str, Any] = Field(default_factory=dict)


class SortBinding(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    mention: str
    candidate_key: str = Field(alias="candidateKey")
    direction: Literal["ASC", "DESC"] = "DESC"


class ClarificationOutput(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    condition_id: str | None = Field(default=None, alias="conditionId")
    code: str
    message: str


class AdjudicationResult(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    condition_bindings: list[ConditionBinding] = Field(default_factory=list, alias="conditionBindings")
    sort_bindings: list[SortBinding] = Field(default_factory=list, alias="sortBindings")
    clarifications: list[ClarificationOutput] = Field(default_factory=list)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_semantic_plan_models.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add fund-screening-service/app/models/semantic_plan.py fund-screening-service/tests/unit/test_semantic_plan_models.py
git commit -m "feat: add semantic plan and adjudication pydantic schemas"
```

---

### Task 2: Strict Direct Probe & Rules

**Files:**
- Create: `app/graph/direct_probe.py`
- Test: `tests/unit/test_direct_probe.py`

**Interfaces:**
- Consumes: `normalized_query`, `candidate_pool`, `previous_dsl`
- Produces: `is_direct: bool`, `direct_kind: str | None`, `direct_dsl: FundScreeningDsl | None`

- [ ] **Step 1: Write the failing test**

```python
# tests/unit/test_direct_probe.py
import pytest
from app.graph.direct_probe import probe_strict_direct
from app.models.candidate import CandidateContract


def test_probe_strict_direct_pure_fund_code():
    candidates = [
        CandidateContract(
            domain="entity",
            key="001938.OF",
            name="中欧时代先锋股票A",
            aliases=("001938",),
            binding_role="FUND",
            preferred_metric="f_info_fundcode",
            supported_operators=("=", "!="),
        )
    ]
    is_direct, direct_dsl, reason = probe_strict_direct("001938", candidates)
    assert is_direct is True
    assert direct_dsl is not None
    assert reason == "EXACT_FUND_CODE"
    assert direct_dsl.children[0].metric.children[0].value == "001938.OF"


def test_probe_rejects_query_with_or_logic():
    candidates = [
        CandidateContract(domain="metric", key="f_info_existingyear", name="成立年限")
    ]
    is_direct, direct_dsl, reason = probe_strict_direct("成立大于5年或者大于3年", candidates)
    assert is_direct is False
    assert direct_dsl is None
    assert "OR_OR_NOT_DETECTED" in reason
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_direct_probe.py`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.graph.direct_probe'`

- [ ] **Step 3: Write minimal implementation**

```python
# app/graph/direct_probe.py
from __future__ import annotations

import re
from typing import Sequence
from app.models.candidate import CandidateContract
from app.models.dsl import FundScreeningDsl, BusinessGroup, SectorExpression, SectorLeaf, MetricExpression


DEFAULT_SECTOR_ID = "1000019220000000"


def probe_strict_direct(
    query: str,
    candidates: Sequence[CandidateContract],
    previous_dsl: dict | None = None,
) -> tuple[bool, FundScreeningDsl | None, str]:
    """严格判定是否可跳过所有生成式 LLM 直接构建 DSL。"""
    clean_q = query.strip()

    # 1. 复杂逻辑判定
    if any(k in clean_q for k in ("或者", "或", "OR", "排除", "不是", "NOT", "且（", "且(")):
        return False, None, "OR_OR_NOT_DETECTED"

    # 2. 多分句判定
    if len(re.split(r"[;；\n]+", clean_q)) > 1:
        return False, None, "MULTI_SEGMENT_DETECTED"

    # 3. 纯基金代码判定 (001938 / 001938.OF)
    if re.fullmatch(r"\d{6}(\.[A-Za-z]+)?", clean_q):
        code_key = clean_q if "." in clean_q else f"{clean_q}.OF"
        dsl = FundScreeningDsl(
            version="fund-screening/v1",
            operator="AND",
            children=[
                BusinessGroup(
                    sector=SectorExpression(operator="OR", children=[SectorLeaf(sectorId=DEFAULT_SECTOR_ID)]),
                    metric=MetricExpression(
                        operator="AND",
                        children=[
                            MetricExpression(
                                operator="=",
                                metric="f_info_fundcode",
                                value=code_key,
                                unit="ENTITY_REF",
                                params=[],
                            )
                        ],
                    ),
                    sort=[],
                    limit=20,
                )
            ],
            sort=[],
            limit=20,
        )
        return True, dsl, "EXACT_FUND_CODE"

    # 4. 精确单个实体匹配且无操作符修饰 (例如仅输入 "易方达蓝筹精选")
    has_operator = any(op in clean_q for op in (">", "<", "=", "大于", "小于", "超过", "低于", "高于"))
    if not has_operator:
        exact_matches = [
            c for c in candidates
            if c.domain == "entity" and (c.name == clean_q or clean_q in c.aliases)
        ]
        if len(exact_matches) == 1:
            match = exact_matches[0]
            if match.preferred_metric:
                dsl = FundScreeningDsl(
                    version="fund-screening/v1",
                    operator="AND",
                    children=[
                        BusinessGroup(
                            sector=SectorExpression(operator="OR", children=[SectorLeaf(sectorId=DEFAULT_SECTOR_ID)]),
                            metric=MetricExpression(
                                operator="AND",
                                children=[
                                    MetricExpression(
                                        operator="=",
                                        metric=match.preferred_metric,
                                        value=match.key,
                                        unit=match.value_type,
                                        params=[],
                                    )
                                ],
                            ),
                            sort=[],
                            limit=20,
                        )
                    ],
                    sort=[],
                    limit=20,
                )
                return True, dsl, "EXACT_ENTITY_MATCH"

    return False, None, "REQUIRES_SEMANTIC_PLAN"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_direct_probe.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add fund-screening-service/app/graph/direct_probe.py fund-screening-service/tests/unit/test_direct_probe.py
git commit -m "feat: implement strict direct probe rule engine"
```

---

### Task 3: Semantic Plan Generator (LLM #1) with Structured Output

**Files:**
- Modify: `app/retrieval/llm.py`
- Test: `tests/unit/test_semantic_plan_llm.py`

**Interfaces:**
- Consumes: `query: str`, `previous_dsl: dict | None`
- Produces: `SemanticPlan` (with `groups`, `scopeTree`, `metricTree`, `sortMentions`, `limit`)

- [ ] **Step 1: Write the failing test**

```python
# tests/unit/test_semantic_plan_llm.py
import pytest
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from app.models.semantic_plan import SemanticPlan
from app.retrieval.llm import OpenAiSemanticLlm


@pytest.mark.asyncio
async def test_semantic_plan_llm_generates_plan_tree():
    sample_json = """
    {
      "intent": "CREATE",
      "groupOperator": "AND",
      "groups": [
        {
          "groupId": "g1",
          "scopeTree": {
            "operator": "OR",
            "children": [{"conditionId": "s1", "mention": "消费行业", "domain": "scope"}]
          },
          "metricTree": {
            "operator": "AND",
            "children": [
              {"conditionId": "m1", "mention": "规模大于10亿", "domain": "metric", "operatorHint": ">", "valueHint": 10, "unitHint": "亿"}
            ]
          }
        }
      ],
      "sortMentions": [{"mention": "近一年收益", "direction": "DESC"}],
      "limit": 30
    }
    """
    fake_model = GenericFakeChatModel(messages=iter([sample_json]))
    llm = OpenAiSemanticLlm(chat_model=fake_model)
    plan = await llm.generate_semantic_plan("消费行业规模大于10亿，按近一年收益排序取前30")
    assert isinstance(plan, SemanticPlan)
    assert len(plan.groups) == 1
    assert plan.groups[0].metric_tree.children[0].condition_id == "m1"
    assert plan.limit == 30
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_semantic_plan_llm.py`
Expected: FAIL with `AttributeError: 'OpenAiSemanticLlm' object has no attribute 'generate_semantic_plan'`

- [ ] **Step 3: Write minimal implementation**

In `app/retrieval/llm.py`:
Update prompt and add `generate_semantic_plan(query: str, previous_dsl: dict | None = None) -> SemanticPlan`:
```python
SEMANTIC_PLAN_SYSTEM_PROMPT = """你只负责将用户自然语言基金筛选请求解析为结构化 Semantic Plan。
严格规则：
1. 划分业务分组 groups，组内分别构建 scopeTree 与 metricTree 递归逻辑树。
2. 识别每个逻辑叶子的 conditionId (如 s1, m1)、mention (原始提及)、domain (scope/metric/entity)。
3. 提供 operatorHint (如 >, >=, <, <=, =)、valueHint (数值或字符串)、unitHint (如 年, 亿, %)。
4. 提取 sortMentions 与 limit。
5. 严禁生成任何指标 ID、板块 ID、实体代码或最终 DSL。只能忠实提取用户语言中的条件树结构。
"""
```
Wire `with_structured_output(SemanticPlan)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_semantic_plan_llm.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add fund-screening-service/app/retrieval/llm.py fund-screening-service/tests/unit/test_semantic_plan_llm.py
git commit -m "feat: add semantic plan structured generator for LLM #1"
```

---

### Task 4: Condition-Level Multi-Domain Recall (RecallByCondition)

**Files:**
- Modify: `app/models/candidate.py` (add `bindings: tuple[dict[str, Any], ...]`)
- Modify: `app/pipeline/analysis.py`
- Test: `tests/unit/test_condition_recall.py`

**Interfaces:**
- Consumes: `SemanticPlan`, `metric_retriever`, `scope_retriever`, `entity_retriever`
- Produces: `by_condition: dict[str, ConditionRecallResult]`, `contract_index: dict[str, CandidateContract]`

- [ ] **Step 1: Write the failing test**

```python
# tests/unit/test_condition_recall.py
import pytest
from app.models.candidate import CandidateContract
from app.models.semantic_plan import SemanticPlan, PlanGroup, ScopeTree, ScopeLeaf, MetricTree, MetricLeaf
from app.pipeline.analysis import AnalysisService, RecallByConditionService


class FakeRetriever:
    def __init__(self, items): self.items = items
    async def search(self, query, top_k=10): return self.items


@pytest.mark.asyncio
async def test_condition_level_recall_dispatches_by_domain():
    metric_candidate = CandidateContract("metric", "f_info_fundscale", "基金规模")
    scope_candidate = CandidateContract("scope", "1000002533000000", "普通股票型基金")

    service = RecallByConditionService(
        metric_retriever=FakeRetriever([metric_candidate]),
        scope_retriever=FakeRetriever([scope_candidate]),
        entity_retriever=FakeRetriever([]),
    )

    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                scope_tree=ScopeTree(children=[ScopeLeaf(condition_id="s1", mention="股票基金")]),
                metric_tree=MetricTree(children=[MetricLeaf(condition_id="m1", mention="规模大于10亿")]),
            )
        ]
    )

    by_condition, contract_index = await service.recall(plan)
    assert "s1" in by_condition
    assert "m1" in by_condition
    assert by_condition["s1"]["candidates"][0].key == "1000002533000000"
    assert by_condition["m1"]["candidates"][0].key == "f_info_fundscale"
    assert "f_info_fundscale" in contract_index
    assert "1000002533000000" in contract_index
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_condition_recall.py`
Expected: FAIL with `ImportError: cannot import name 'RecallByConditionService'`

- [ ] **Step 3: Write minimal implementation**

1. In `app/models/candidate.py`:
   Add `bindings: tuple[dict[str, Any], ...] = ()` to `CandidateContract`.
2. In `app/pipeline/analysis.py`:
   Implement `RecallByConditionService` which traverses `SemanticPlan`, extracts all condition leaves (`condition_id`, `mention`, `domain`), calls the appropriate retriever, and constructs both `by_condition` and `contract_index`.

- [ ] **Step 4: Run test to verify it passes**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_condition_recall.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add fund-screening-service/app/models/candidate.py fund-screening-service/app/pipeline/analysis.py fund-screening-service/tests/unit/test_condition_recall.py
git commit -m "feat: implement condition-level multi-domain recall with by_condition index"
```

---

### Task 5: Simple / Full Semantic Router

**Files:**
- Modify: `app/graph/router.py`
- Test: `tests/unit/test_semantic_router.py`

**Interfaces:**
- Consumes: `SemanticPlan`, `by_condition: dict`, `contract_index: dict`
- Produces: `route: Literal["simple", "full"]`, `reason: str`

- [ ] **Step 1: Write the failing test**

```python
# tests/unit/test_semantic_router.py
import pytest
from app.graph.router import classify_plan_route
from app.models.semantic_plan import SemanticPlan, PlanGroup, ScopeTree, ScopeLeaf, MetricTree, MetricLeaf


def test_classify_simple_route_for_single_group_shallow_and():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                scope_tree=ScopeTree(children=[ScopeLeaf(condition_id="s1", mention="混合基金")]),
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(condition_id="m1", mention="规模大于10亿"),
                        MetricLeaf(condition_id="m2", mention="成立超过3年"),
                    ],
                ),
            )
        ]
    )
    by_condition = {
        "s1": {"candidates": [{"score": 0.95}]},
        "m1": {"candidates": [{"score": 0.92}]},
        "m2": {"candidates": [{"score": 0.90}]},
    }
    route, reason = classify_plan_route(plan, by_condition)
    assert route == "simple"
    assert "SINGLE_GROUP_SHALLOW" in reason


def test_classify_full_route_for_multi_groups():
    plan = SemanticPlan(
        group_operator="OR",
        groups=[
            PlanGroup(group_id="g1"),
            PlanGroup(group_id="g2"),
        ]
    )
    route, reason = classify_plan_route(plan, {})
    assert route == "full"
    assert "MULTI_GROUP" in reason
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_semantic_router.py`
Expected: FAIL with `ImportError: cannot import name 'classify_plan_route'`

- [ ] **Step 3: Write minimal implementation**

Implement `classify_plan_route(plan: SemanticPlan, by_condition: dict) -> tuple[Literal["simple", "full"], str]` in `app/graph/router.py`:
- Multi-group -> "full"
- Presence of "NOT" in trees -> "full"
- Tree depth > 2 -> "full"
- Total conditions > 3 -> "full"
- High ambiguity (Top1 vs Top2 score difference < 0.05 and both > 0.7) -> "full"
- Otherwise -> "simple"

- [ ] **Step 4: Run test to verify it passes**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_semantic_router.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add fund-screening-service/app/graph/router.py fund-screening-service/tests/unit/test_semantic_router.py
git commit -m "feat: implement plan-based Simple vs Full deterministic router"
```

---

### Task 6: Candidate Adjudication (LLM #2: Simple & Full)

**Files:**
- Modify: `app/retrieval/llm.py`
- Test: `tests/unit/test_candidate_adjudication.py`

**Interfaces:**
- Consumes: `SemanticPlan`, `by_condition`, `route: Literal["simple", "full"]`
- Produces: `AdjudicationResult` (strictly leaf condition bindings)

- [ ] **Step 1: Write the failing test**

```python
# tests/unit/test_candidate_adjudication.py
import pytest
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from app.models.semantic_plan import SemanticPlan, PlanGroup, MetricTree, MetricLeaf, AdjudicationResult
from app.retrieval.llm import OpenAiSemanticLlm


@pytest.mark.asyncio
async def test_candidate_adjudication_outputs_leaf_bindings_only():
    fake_json = """
    {
      "conditionBindings": [
        {
          "conditionId": "m1",
          "candidateKey": "f_info_fundscale",
          "bindingRole": null,
          "operator": ">",
          "value": 10,
          "unit": "亿",
          "params": {}
        }
      ],
      "sortBindings": [],
      "clarifications": []
    }
    """
    fake_model = GenericFakeChatModel(messages=iter([fake_json]))
    llm = OpenAiSemanticLlm(chat_model=fake_model)

    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(children=[MetricLeaf(condition_id="m1", mention="规模大于10亿")]),
            )
        ]
    )
    by_condition = {
        "m1": {
            "mention": "规模大于10亿",
            "domain": "metric",
            "candidates": [{"key": "f_info_fundscale", "name": "基金规模", "supported_operators": [">"]}],
        }
    }

    result = await llm.adjudicate_candidates(plan, by_condition, route="simple")
    assert isinstance(result, AdjudicationResult)
    assert len(result.condition_bindings) == 1
    assert result.condition_bindings[0].candidate_key == "f_info_fundscale"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_candidate_adjudication.py`
Expected: FAIL with `AttributeError: 'OpenAiSemanticLlm' object has no attribute 'adjudicate_candidates'`

- [ ] **Step 3: Write minimal implementation**

In `app/retrieval/llm.py`:
- Add `ADJUDICATION_SYSTEM_PROMPT_LEAF_ONLY`:
  Instructs LLM to only output `conditionBindings` for given `conditionId`s using candidates in `by_condition`.
- Add `adjudicate_candidates(self, plan: SemanticPlan, by_condition: dict, route: str) -> AdjudicationResult`.
- Ensure output validates against `AdjudicationResult`.

- [ ] **Step 4: Run test to verify it passes**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_candidate_adjudication.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add fund-screening-service/app/retrieval/llm.py fund-screening-service/tests/unit/test_candidate_adjudication.py
git commit -m "feat: implement candidate adjudication LLM #2 for leaf-only binding"
```

---

### Task 7: AST CommandBuilder with Semantic Plan Trees & Condition Bindings

**Files:**
- Modify: `app/pipeline/builder.py`
- Test: `tests/unit/test_ast_command_builder.py`

**Interfaces:**
- Consumes: `SemanticPlan`, `AdjudicationResult`, `contract_index: dict[str, CandidateContract]`
- Produces: `FundScreeningDsl` (preserving recursive logic tree, default sector fallback, entity role resolution)

- [ ] **Step 1: Write the failing test**

```python
# tests/unit/test_ast_command_builder.py
import pytest
from app.models.candidate import CandidateContract
from app.models.semantic_plan import (
    SemanticPlan,
    PlanGroup,
    ScopeTree,
    ScopeLeaf,
    MetricTree,
    MetricLeaf,
    AdjudicationResult,
    ConditionBinding,
)
from app.pipeline.builder import CommandBuilder


def test_command_builder_reconstructs_nested_metric_tree():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(condition_id="m1", mention="规模大于10亿"),
                        MetricTree(
                            operator="OR",
                            children=[
                                MetricLeaf(condition_id="m2", mention="成立大于5年"),
                                MetricLeaf(condition_id="m3", mention="成立大于3年"),
                            ],
                        ),
                    ],
                ),
            )
        ]
    )
    adjudication = AdjudicationResult(
        condition_bindings=[
            ConditionBinding(condition_id="m1", candidate_key="f_info_fundscale", operator=">", value=10, unit="亿"),
            ConditionBinding(condition_id="m2", candidate_key="f_info_existingyear", operator=">", value=5, unit="年"),
            ConditionBinding(condition_id="m3", candidate_key="f_info_existingyear", operator=">", value=3, unit="年"),
        ]
    )
    contracts = {
        "f_info_fundscale": CandidateContract("metric", "f_info_fundscale", "基金规模"),
        "f_info_existingyear": CandidateContract("metric", "f_info_existingyear", "成立年限"),
        "1000019220000000": CandidateContract("scope", "1000019220000000", "全市场"),
    }

    builder = CommandBuilder()
    dsl = builder.build_from_plan(plan, adjudication, contracts)

    assert dsl.version == "fund-screening/v1"
    group = dsl.children[0]
    # Sector fallback
    assert group.sector.children[0].sector_id == "1000019220000000"
    # Nested metric structure: m1 AND (m2 OR m3)
    metric_expr = group.metric
    assert metric_expr.operator == "AND"
    assert metric_expr.children[0].metric == "f_info_fundscale"
    sub_or = metric_expr.children[1]
    assert sub_or.operator == "OR"
    assert len(sub_or.children) == 2
    assert sub_or.children[0].value == 5
    assert sub_or.children[1].value == 3
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_ast_command_builder.py`
Expected: FAIL with `AttributeError: 'CommandBuilder' object has no attribute 'build_from_plan'`

- [ ] **Step 3: Write minimal implementation**

In `app/pipeline/builder.py`:
- Add `build_from_plan(self, plan: SemanticPlan, adjudication: AdjudicationResult, contracts: dict[str, CandidateContract]) -> FundScreeningDsl`.
- Reconstruct recursive `SectorExpression` from `plan.groups[i].scope_tree`.
- Reconstruct recursive `MetricExpression` from `plan.groups[i].metric_tree` by mapping `condition_id` to its `ConditionBinding`.
- Handle Entity Binding:
  - If entity candidate has `binding_role == "BENCHMARK"`, attach it as parameter `indexCode` to target metric.
  - If entity candidate binds as condition, attach `preferred_metric` condition.
- Attach sort items and limit.

- [ ] **Step 4: Run test to verify it passes**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_ast_command_builder.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add fund-screening-service/app/pipeline/builder.py fund-screening-service/tests/unit/test_ast_command_builder.py
git commit -m "feat: implement AST CommandBuilder from SemanticPlan and ConditionBindings"
```

---

### Task 8: Enhanced Validator & Condition-Level Clarifications

**Files:**
- Modify: `app/pipeline/validator.py`
- Test: `tests/unit/test_semantic_validator.py`

**Interfaces:**
- Consumes: `FundScreeningDsl`, `contracts: dict[str, CandidateContract]`
- Produces: `ValidationResult` (with repairs, `conditionId`-tagged clarifications, negative storage convention handling)

- [ ] **Step 1: Write the failing test**

```python
# tests/unit/test_semantic_validator.py
import pytest
from app.models.candidate import CandidateContract
from app.models.dsl import FundScreeningDsl, BusinessGroup, SectorExpression, SectorLeaf, MetricExpression
from app.pipeline.validator import validate_and_repair


def test_validator_converts_drawdown_negative_and_inverts_operator():
    contract = CandidateContract(
        domain="metric",
        key="f_risk_maxdownside",
        name="最大回撤",
        result_contract={"storageConvention": "NEGATIVE_PERCENT", "supportedOperators": [">=", "<="]},
    )
    dsl = FundScreeningDsl(
        version="fund-screening/v1",
        operator="AND",
        children=[
            BusinessGroup(
                sector=SectorExpression(operator="OR", children=[SectorLeaf(sectorId="1000019220000000")]),
                metric=MetricExpression(
                    operator="AND",
                    children=[
                        MetricExpression(operator="<=", metric="f_risk_maxdownside", value=15.0, unit="%")
                    ],
                ),
            )
        ],
        limit=20,
    )
    res = validate_and_repair(dsl, {"f_risk_maxdownside": contract})
    leaf = res.dsl.children[0].metric.children[0]
    assert leaf.value == -15.0
    assert leaf.operator == ">="
    assert "NEGATIVE_DRAWDOWN_STORAGE" in res.repairs
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_semantic_validator.py`
Expected: Check existing behavior or fail if operator invert / result contract check needs refinement.

- [ ] **Step 3: Write minimal implementation**

Refine `validate_and_repair` in `app/pipeline/validator.py` to ensure:
1. `storageConvention == "NEGATIVE_PERCENT"` properly handles `<` / `<=` / `>` / `>=` operator inversion and negative value negation.
2. If required parameters are missing from candidate parameters, output clarification containing `condition_id`.
3. Support recursive `MetricExpression` and `SectorExpression` depth validation without crashing.

- [ ] **Step 4: Run test to verify it passes**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_semantic_validator.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add fund-screening-service/app/pipeline/validator.py fund-screening-service/tests/unit/test_semantic_validator.py
git commit -m "feat: enhance validator with negative storage convention and condition clarifications"
```

---

### Task 9: LangGraph State & Workflow Assembly

**Files:**
- Modify: `app/graph/state.py`
- Modify: `app/graph/nodes.py`
- Modify: `app/graph/workflow.py`
- Modify: `app/pipeline/orchestrator.py`
- Test: `tests/unit/test_graph_semantic_pipeline.py`

**Interfaces:**
- Consumes: All components from Tasks 1-8
- Produces: Compiled `StateGraph` executing:
  `START -> query_preprocess -> strict_direct_probe -> (direct_build | semantic_plan) -> recall_by_condition -> simple_full_router -> (simple_adjudication | full_adjudication) -> command_build -> validator -> (finalize | repair | clarification)`

- [ ] **Step 1: Write the failing test**

```python
# tests/unit/test_graph_semantic_pipeline.py
import pytest
from app.graph.workflow import build_screening_graph
from app.graph.nodes import ScreeningGraphNodes
from app.models.candidate import CandidateContract


@pytest.mark.asyncio
async def test_graph_executes_direct_route_for_fund_code():
    nodes = ScreeningGraphNodes(...) # Mocked / stubbed nodes
    graph = build_screening_graph(nodes)
    final_state = await graph.ainvoke({"query": "001938"})
    assert final_state.get("final_dsl") is not None
    assert final_state.get("route") == "direct"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_graph_semantic_pipeline.py`
Expected: FAIL with missing routing or state edges.

- [ ] **Step 3: Write minimal implementation**

1. In `app/graph/state.py`:
   Add `semantic_plan: dict | None`, `by_condition_pool: dict | None`, `adjudication_result: dict | None`, `route: str | None`.
2. In `app/graph/nodes.py`:
   - `strict_direct_probe`: invoke `probe_strict_direct`
   - `semantic_plan`: invoke LLM #1
   - `recall_by_condition`: invoke condition-level recall
   - `simple_full_router`: invoke router
   - `simple_adjudication` & `full_adjudication`: invoke LLM #2
   - `command_build`: invoke AST CommandBuilder
3. In `app/graph/workflow.py`:
   Wire up the conditional edges according to Spec Section 2.
4. In `app/pipeline/orchestrator.py`:
   Map new nodes to the 5 external SSE events (`query_preprocess`, `query_analysis_and_recall`, `command_build`, `validator_and_repair`, `command_finalize`).

- [ ] **Step 4: Run test to verify it passes**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_graph_semantic_pipeline.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add fund-screening-service/app/graph/state.py fund-screening-service/app/graph/nodes.py fund-screening-service/app/graph/workflow.py fund-screening-service/app/pipeline/orchestrator.py fund-screening-service/tests/unit/test_graph_semantic_pipeline.py
git commit -m "feat: assemble complete semantic routing workflow in LangGraph"
```

---

### Task 10: Multi-Round Incremental Edit & Checkpointing

**Files:**
- Modify: `app/pipeline/editor.py`
- Modify: `app/graph/nodes.py`
- Test: `tests/unit/test_multi_round_semantic_edit.py`

**Interfaces:**
- Consumes: `previous_dsl`, `query: str`
- Produces: Direct DSL Patch for simple edits (limit, sort) / Partial condition recall & patch for complex edits.

- [ ] **Step 1: Write the failing test**

```python
# tests/unit/test_multi_round_semantic_edit.py
import pytest
from app.pipeline.editor import apply_simple_edit
from app.models.dsl import FundScreeningDsl


def test_multi_round_edit_limit_preserves_ast():
    initial_dsl = FundScreeningDsl.model_validate({
        "version": "fund-screening/v1",
        "operator": "AND",
        "children": [
            {
                "sector": {"operator": "OR", "children": [{"sectorId": "1000019220000000"}]},
                "metric": {"operator": "AND", "children": [{"metric": "f_info_fundscale", "operator": ">", "value": 10}]},
            }
        ],
        "limit": 20,
    })
    edited = apply_simple_edit(initial_dsl, "改成前50只")
    assert edited.limit == 50
    assert edited.children[0].metric.children[0].value == 10
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_multi_round_semantic_edit.py`
Expected: Verify failure on new patch patterns.

- [ ] **Step 3: Write minimal implementation**

Enhance `editor.py` to support:
- Modifying limit ("改成前50只", "取前100只")
- Modifying sort direction ("按收益降序", "升序")
- Removing condition ("去掉规模限制") without re-generating full DSL with LLM.

- [ ] **Step 4: Run test to verify it passes**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_multi_round_semantic_edit.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add fund-screening-service/app/pipeline/editor.py fund-screening-service/tests/unit/test_multi_round_semantic_edit.py
git commit -m "feat: enhance multi-round incremental edit and patch engine"
```

---

### Task 11: End-to-End Acceptance Tests (Covering All 14 Spec Scenarios)

**Files:**
- Create: `tests/integration/test_semantic_routing_acceptance.py`
- Modify: `tests/api/test_commands.py`
- Test: All 14 scenarios from Spec Section 15

**Interfaces:**
- Validates:
  1. 基金代码 Strict Direct，0次 LLM
  2. 单指标严格表达式 Direct 或安全进入 Simple
  3. 单 Group 多个 AND 条件进入 Simple
  4. `A AND (B OR C)` 保持递归逻辑结构
  5. 多 Group 且每组独立 scope
  6. 同一指标不同参数重复出现
  7. 基金公司、基金经理、股票和指数实体正确绑定
  8. 指数实体可绑定为指标条件或 `indexCode` 参数
  9. 必填参数缺失产生 condition级 clarification
  10. 最大回撤根据结果契约转换负值口径
  11. 简单编辑不调用语义 LLM
  12. 复杂编辑只召回和修改受影响的 condition
  13. HTTP 和 WebSocket 产生相同最终 DSL
  14. 烟测与运行态冒烟测试

- [ ] **Step 1: Write the failing acceptance tests**

```python
# tests/integration/test_semantic_routing_acceptance.py
import pytest
from app.main import create_app
from fastapi.testclient import TestClient


def test_scenario_1_strict_direct_fund_code():
    client = TestClient(create_app())
    resp = client.post("/v1/fund-screening/commands", json={"query": "001938"})
    assert resp.status_code == 200
    dsl = resp.json()["dsl"]
    assert dsl["children"][0]["metric"]["children"][0]["value"] == "001938.OF"


def test_scenario_4_nested_and_or_logic():
    # 测试 A AND (B OR C) 嵌套逻辑
    client = TestClient(create_app())
    query = "规模大于10亿，且（成立大于5年或者成立大于3年）"
    resp = client.post("/v1/fund-screening/commands", json={"query": query})
    assert resp.status_code == 200
    dsl = resp.json()["dsl"]
    metric_expr = dsl["children"][0]["metric"]
    assert metric_expr["operator"] == "AND"
    assert any(c.get("operator") == "OR" for c in metric_expr["children"])
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/integration/test_semantic_routing_acceptance.py`
Expected: FAIL

- [ ] **Step 3: Implement missing adjustments and wire runtime**

Fix any edge cases across runtime, compiler adapter, and endpoint handlers.

- [ ] **Step 4: Run full test suite to verify all tests pass**

Run: `& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit tests/integration -v`
Expected: All tests PASS!

- [ ] **Step 5: Commit**

```bash
git add fund-screening-service/tests/integration/test_semantic_routing_acceptance.py fund-screening-service/tests/api/test_commands.py
git commit -m "test: add comprehensive acceptance tests for all 14 semantic routing scenarios"
```

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-29-fund-screening-semantic-routing-implementation.md`. Two execution options:

1. **Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
