# 基金筛选 Agent LangGraph 迁移实施计划 (Fund Screening LangGraph Migration Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将当前显式 Python 编排的基金筛选编译链迁移为 LangChain + LangGraph 的确定性状态图编译工作流，支持直接/简单/完整三级路由、有界的参数与指标校验修复循环、多轮上下文 Checkpoint，以及指标函数签名参数契约。

**Architecture:** 现有 PGVector 检索器、Lexical 实体匹配、Candidate Contract、Command Builder、Validator、FastAPI 和 Codex bridge 保持为底层领域组件。由 LangGraph `StateGraph` 与 `FundScreeningState` 进行显式有状态编排；LangChain 结构化输出严格限制在 Pre-Recall Plan 与 Candidate Adjudication 两处调用；路由、召回、构建、修复与交付保持程序化；HTTP 与 WebSocket 统一复用同一个已编译 Graph 实例。

**Tech Stack:** Python 3.11+、FastAPI、LangChain、LangGraph、Pydantic v2、PostgreSQL/PGVector、Redis (可选)、httpx、pytest

**Spec:** `docs/superpowers/specs/2026-09-28-fund-screening-langgraph-migration-design.md`

## Global Constraints

- **确定性编译优先：** 不使用自主推理的 ReAct Agent 或 `AgentExecutor`；LLM 不得自主选择任意工具。
- **调用次数受限：** `full` 路径恰好两次基础 LLM 调用（Plan 与 Adjudication）；`simple` 路径最多一次轻量解析；`direct` 路径为零次。
- **候选受限裁决：** LLM 只能使用 `Candidate Contract Index` 中存在的指标、板块、实体与参数，严禁由 LLM 凭空捏造 key。
- **循环次数上限：** 校验与机械修复循环上限为 2 次；超过上限立即输出结构化 `clarification`，禁止在修复节点调用 LLM。
- **板块默认约束：** 每个业务组必须包含板块；用户未显式指定时由程序补充默认全市场板块 `1000019220000000`。
- **单图统一服务：** HTTP (`/commands`) 与 WebSocket (`/ws`) 必须挂载并调用同一个已编译 `StateGraph` 实例。
- **参数命名规范：** 指标函数参数契约使用 `parameters`，运行时表达式使用 `params`，严禁重新引入 `source` 字段。
- **上下文绑定参数：** `windCode` / `FUND_CODE` 由筛选执行引擎在运行时绑定，筛选 DSL 中允许缺省，Validator 不强制要求输入。
- **TDD 流程纪律：** 严格遵循 RED → GREEN → 提交，每个任务独立可评审，现有 34 个测试全量通过且不回退。

## File Structure

```text
fund-screening-service/
├── app/
│   ├── graph/
│   │   ├── __init__.py               # Graph 模块导出
│   │   ├── state.py                  # FundScreeningState TypedDict 与初始化助手
│   │   ├── nodes.py                  # 阶段节点包装函数（输入/输出 Partial State、耗时与 Trace 记录）
│   │   ├── router.py                 # direct / simple / full 确定性路由与条件边决策
│   │   ├── repair.py                 # 机械修复、修复次数计数与 Clarification 决策
│   │   ├── checkpoint.py             # MemorySaver / RedisSaver 构造器与 thread_id 绑定
│   │   ├── workflow.py               # StateGraph 组装、条件分支连接与编译
│   │   └── compiler.py               # 兼容旧 RagCommandCompiler 接口的适配器
│   ├── models/
│   │   ├── candidate.py              # CandidateContract (扩展 parameters 字段)
│   │   ├── dsl.py                    # MetricPredicate / MetricExpression (增加 params 字段)
│   │   ├── semantic.py               # LangChain 结构化输出 Pydantic Schema (Plan 与 Adjudication)
│   │   └── parameters.py             # 指标参数定义与参数实例模型
│   ├── catalog/
│   │   ├── loader.py                 # Catalog 加载器（扩展 parameters 解析与绑定）
│   │   └── parameter_rules.py        # 指标参数推导与分类规则表
│   ├── pipeline/
│   │   ├── orchestrator.py           # 切换至 Graph 驱动执行
│   │   ├── validator.py              # 扩展参数类型、枚举、日期顺序及上限校验
│   │   └── builder.py                # 传递 params 至 MetricPredicate
│   ├── retrieval/
│   │   └── llm.py                    # 接入 ChatOpenAI structured output 与错误重试
│   ├── api/
│   │   └── commands.py               # HTTP / WebSocket 统一接入同一个已编译 Graph
│   ├── runtime.py                    # 组装并编译 Graph 单例
│   └── main.py                       # 挂载配置化应用
└── tests/
    ├── unit/
    │   ├── test_graph_state.py       # Task 1: State 定义与初始化单测
    │   ├── test_graph_nodes.py       # Task 2: 节点包装与 Partial Update 契约单测
    │   ├── test_structured_llm.py    # Task 3: LangChain Structured Output 单测
    │   ├── test_graph_routing.py     # Task 4: 三级路由分类与条件边单测
    │   ├── test_graph_repair_loop.py # Task 5: 修复循环上限与 Clarification 单测
    │   ├── test_graph_checkpoint.py  # Task 6: 多轮编辑与 Checkpoint 状态恢复单测
    │   ├── test_metric_parameter_validation.py # Task 8: 参数校验与日期范围单测
    │   └── test_parameter_rules.py   # Task 8: 169 个指标参数规则推导覆盖单测
    └── api/
        ├── test_commands.py          # Task 7: HTTP 命令接口回归
        ├── test_websocket.py         # Task 7: WebSocket 事件流回归
        └── test_graph_api.py         # Task 7: HTTP/WS 等价性及阶段事件校验
```

---

### Task 1: Graph 基础状态 (Graph Foundation State & Dependency Baseline)

**Files:**
- Create: `fund-screening-service/app/graph/__init__.py`
- Create: `fund-screening-service/app/graph/state.py`
- Modify: `fund-screening-service/pyproject.toml`
- Test: `fund-screening-service/tests/unit/test_graph_state.py`

**Interfaces:**
- Produces:
  - `FundScreeningState(TypedDict)`: 严格对齐设计规格第 3 节全部字段。
  - `TraceEvent(TypedDict)`: 包含 `stage`, `duration_ms`, `summary`, `status`。
  - `empty_state(query: str, conversation_id: str | None = None, previous_dsl: dict | None = None) -> FundScreeningState`: 安全初始化所有列表与字典字段。
- Consumes: 无（基础领域类型与标准库）。

- [ ] **Step 1: 编写失败测试**

创建 `fund-screening-service/tests/unit/test_graph_state.py`:

```python
from app.graph.state import FundScreeningState, empty_state


def test_empty_state_initializes_bounded_repair_workflow() -> None:
    state = empty_state("筛选低回撤基金", conversation_id="conv-123")
    assert state["query"] == "筛选低回撤基金"
    assert state["conversation_id"] == "conv-123"
    assert state["repair_attempts"] == 0
    assert state["candidate_pool"] == []
    assert state["candidate_contract_index"] == {}
    assert state["validation_errors"] == []
    assert state["repairs"] == []
    assert state["trace"] == []
    assert state["intent"] == "CREATE"
    assert state["edit_complexity"] == "NONE"
    assert state["route"] == "full"
    assert state["draft_dsl"] is None
    assert state["final_dsl"] is None
    assert state["clarification"] is None


def test_state_accepts_previous_dsl_for_editing() -> None:
    prev = {"version": "fund-screening/v1", "operator": "AND", "children": [], "limit": 20}
    state = empty_state("改成前50只", previous_dsl=prev)
    assert state["previous_dsl"] == prev
    assert state["intent"] == "EDIT"
```

- [ ] **Step 2: 运行测试验证失败 (RED)**

运行命令:
```powershell
python -m pytest tests/unit/test_graph_state.py -v
```
预期输出:
```text
ImportError: No module named 'app.graph'
```

- [ ] **Step 3: 编写最小实现与依赖声明**

在 `fund-screening-service/pyproject.toml` 中的 `dependencies` 加入 `langchain-core` 和 `langgraph`:
```toml
dependencies = [
    "fastapi>=0.115.0",
    "uvicorn[standard]>=0.30.0",
    "pydantic>=2.9.0",
    "pydantic-settings>=2.5.0",
    "httpx>=0.27.0",
    "asyncpg>=0.29.0",
    "langchain-core>=0.3.0",
    "langgraph>=0.2.0",
]
```

创建 `fund-screening-service/app/graph/__init__.py`:
```python
from app.graph.state import FundScreeningState, empty_state

__all__ = ["FundScreeningState", "empty_state"]
```

创建 `fund-screening-service/app/graph/state.py`:
```python
from __future__ import annotations

import uuid
from typing import Any, Literal, TypedDict


class TraceEvent(TypedDict):
    stage: str
    duration_ms: float
    summary: dict[str, Any]
    status: Literal["completed", "failed", "skipped"]


class ClarificationPayload(TypedDict):
    code: str
    message: str
    suggestions: list[str]


class FundScreeningState(TypedDict, total=False):
    request_id: str
    conversation_id: str | None
    query: str
    normalized_query: str
    previous_dsl: dict[str, Any] | None
    intent: Literal["CREATE", "EDIT"]
    edit_complexity: Literal["NONE", "SIMPLE", "COMPLEX"]
    route: Literal["direct", "simple", "full"]

    recall_plan: dict[str, Any] | None
    candidate_pool: list[dict[str, Any]]
    candidate_contract_index: dict[str, dict[str, Any]]
    decision: dict[str, Any] | None

    draft_dsl: dict[str, Any] | None
    final_dsl: dict[str, Any] | None
    validation_errors: list[dict[str, Any]]
    repairs: list[dict[str, Any]]
    clarification: ClarificationPayload | None
    repair_attempts: int
    trace: list[TraceEvent]


def empty_state(
    query: str,
    conversation_id: str | None = None,
    previous_dsl: dict[str, Any] | None = None,
) -> FundScreeningState:
    intent: Literal["CREATE", "EDIT"] = "EDIT" if previous_dsl else "CREATE"
    return {
        "request_id": str(uuid.uuid4()),
        "conversation_id": conversation_id,
        "query": query,
        "normalized_query": query,
        "previous_dsl": previous_dsl,
        "intent": intent,
        "edit_complexity": "NONE",
        "route": "full",
        "recall_plan": None,
        "candidate_pool": [],
        "candidate_contract_index": {},
        "decision": None,
        "draft_dsl": None,
        "final_dsl": None,
        "validation_errors": [],
        "repairs": [],
        "clarification": None,
        "repair_attempts": 0,
        "trace": [],
    }
```

- [ ] **Step 4: 运行测试验证通过 (GREEN) 与全量回归**

运行命令:
```powershell
python -m pytest tests/unit/test_graph_state.py tests -v
```
预期输出:
```text
35 passed in 0.90s
```

- [ ] **Step 5: 提交更改**

运行命令:
```bash
git add fund-screening-service/pyproject.toml fund-screening-service/app/graph/state.py fund-screening-service/app/graph/__init__.py fund-screening-service/tests/unit/test_graph_state.py
git commit -m "feat: define fund screening graph state and dependency baseline"
```

---

### Task 2: 节点包装 (Node Wrapping & Pipeline Step Encapsulation)

**Files:**
- Create: `fund-screening-service/app/graph/nodes.py`
- Test: `fund-screening-service/tests/unit/test_graph_nodes.py`

**Interfaces:**
- Produces: `ScreeningGraphNodes` 类，包含纯节点方法：
  - `query_preprocess(state: FundScreeningState) -> dict[str, Any]`
  - `pre_recall_plan(state: FundScreeningState) -> dict[str, Any]`
  - `parallel_recall(state: FundScreeningState) -> dict[str, Any]`
  - `candidate_adjudication(state: FundScreeningState) -> dict[str, Any]`
  - `command_build(state: FundScreeningState) -> dict[str, Any]`
  - `validator(state: FundScreeningState) -> dict[str, Any]`
  - `command_finalize(state: FundScreeningState) -> dict[str, Any]`
- Consumes:
  - `preprocess` from `app.pipeline.preprocess`
  - `AnalysisService` from `app.pipeline.analysis`
  - `CommandBuilder` from `app.pipeline.builder`
  - `validate_and_repair` from `app.pipeline.validator`
  - `CandidatePool`, `CandidateContract` from `app.models.candidate`

- [ ] **Step 1: 编写失败测试**

创建 `fund-screening-service/tests/unit/test_graph_nodes.py`:

```python
import pytest
from app.graph.nodes import ScreeningGraphNodes
from app.graph.state import empty_state
from app.models.candidate import CandidateContract
from app.pipeline.analysis import AnalysisService, CandidatePool, ConditionPlan, RecallPlan
from app.pipeline.builder import CommandBuilder


class FakeRetriever:
    async def search(self, query: str, limit: int = 10):
        return [CandidateContract("metric", "f_risk_maxdownside", "最大回撤")]


@pytest.mark.asyncio
async def test_nodes_execute_stages_and_produce_trace() -> None:
    analysis = AnalysisService(
        plan_generator=lambda q: RecallPlan(("metric",), (ConditionPlan("c1", "回撤", "metric"),)),
        metric_retriever=FakeRetriever(),
        scope_retriever=FakeRetriever(),
        entity_retriever=FakeRetriever(),
        adjudicator=lambda q, p, pool: {
            "groups": [{
                "sectorIds": ["1000019220000000"],
                "metrics": [{"metric": "f_risk_maxdownside", "operator": ">=", "value": -15, "unit": "PERCENT"}],
            }],
            "limit": 20,
        },
    )
    nodes = ScreeningGraphNodes(analysis=analysis, builder=CommandBuilder())
    state = empty_state("筛选最大回撤小于15%的基金")

    p1 = await nodes.query_preprocess(state)
    assert "normalized_query" in p1
    assert p1["trace"][-1]["stage"] == "query_preprocess"

    p2 = await nodes.pre_recall_plan({**state, **p1})
    assert p2["recall_plan"] is not None
    assert p2["trace"][-1]["stage"] == "pre_recall_plan"

    p3 = await nodes.parallel_recall({**state, **p1, **p2})
    assert len(p3["candidate_pool"]) > 0
    assert "f_risk_maxdownside" in p3["candidate_contract_index"]

    p4 = await nodes.candidate_adjudication({**state, **p1, **p2, **p3})
    assert p4["decision"] is not None

    p5 = await nodes.command_build({**state, **p1, **p2, **p3, **p4})
    assert p5["draft_dsl"] is not None

    p6 = await nodes.validator({**state, **p1, **p2, **p3, **p4, **p5})
    assert p6["validation_errors"] == []

    p7 = await nodes.command_finalize({**state, **p1, **p2, **p3, **p4, **p5, **p6})
    assert p7["final_dsl"] is not None
    assert p7["trace"][-1]["stage"] == "command_finalize"
```

- [ ] **Step 2: 运行测试验证失败 (RED)**

运行命令:
```powershell
python -m pytest tests/unit/test_graph_nodes.py -v
```
预期输出:
```text
ImportError: No module named 'app.graph.nodes'
```

- [ ] **Step 3: 编写最小节点实现**

创建 `fund-screening-service/app/graph/nodes.py`:

```python
from __future__ import annotations

import time
from typing import Any

from app.graph.state import FundScreeningState, TraceEvent
from app.models.candidate import CandidateContract
from app.models.dsl import FundScreeningDsl
from app.pipeline.analysis import AnalysisService, CandidatePool
from app.pipeline.builder import CommandBuilder
from app.pipeline.preprocess import preprocess
from app.pipeline.validator import validate_and_repair


def _make_trace(stage: str, started_at: float, summary: dict[str, Any], status: str = "completed") -> TraceEvent:
    return {
        "stage": stage,
        "duration_ms": round((time.perf_counter() - started_at) * 1000, 2),
        "summary": summary,
        "status": status,  # type: ignore[typeddict-item]
    }


class ScreeningGraphNodes:
    def __init__(self, analysis: AnalysisService, builder: CommandBuilder | None = None) -> None:
        self._analysis = analysis
        self._builder = builder or CommandBuilder()

    async def query_preprocess(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        prep = preprocess(state["query"], state.get("previous_dsl"))
        intent = "EDIT" if prep.previous_dsl else "CREATE"
        complexity = "SIMPLE" if prep.edit_kind == "simple" else ("COMPLEX" if intent == "EDIT" else "NONE")
        event = _make_trace("query_preprocess", t0, {"intent": intent, "complexity": complexity})
        return {
            "normalized_query": prep.normalized_query,
            "intent": intent,
            "edit_complexity": complexity,
            "trace": [*state.get("trace", []), event],
        }

    async def pre_recall_plan(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        plan = await self._analysis._make_plan(state["normalized_query"])
        plan_dict = {
            "domains": list(plan.domains),
            "conditions": [
                {"condition_id": c.condition_id, "mention": c.mention, "domain": c.domain}
                for c in plan.conditions
            ],
        }
        event = _make_trace("pre_recall_plan", t0, {"condition_count": len(plan.conditions)})
        return {
            "recall_plan": plan_dict,
            "trace": [*state.get("trace", []), event],
        }

    async def parallel_recall(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        plan_data = state["recall_plan"] or {"domains": ["metric", "scope", "entity"], "conditions": []}
        from app.pipeline.analysis import ConditionPlan, RecallPlan
        conditions = tuple(
            ConditionPlan(c["condition_id"], c["mention"], c["domain"])
            for c in plan_data.get("conditions", [])
        )
        plan = RecallPlan(tuple(plan_data.get("domains", ())), conditions)
        pool = await self._analysis._recall(state["normalized_query"], plan)
        raw_pool = [
            {
                "domain": c.domain,
                "key": c.key,
                "name": c.name,
                "aliases": list(c.aliases),
                "supported_operators": list(c.supported_operators),
                "value_type": c.value_type,
                "supported_usage": list(c.supported_usage),
                "binding_role": c.binding_role,
                "preferred_metric": c.preferred_metric,
                "score": c.score,
            }
            for c in pool.candidates
        ]
        contract_index = {c["key"]: c for c in raw_pool}
        event = _make_trace("parallel_recall", t0, {"candidate_count": len(raw_pool)})
        return {
            "candidate_pool": raw_pool,
            "candidate_contract_index": contract_index,
            "trace": [*state.get("trace", []), event],
        }

    async def candidate_adjudication(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        contracts = [
            CandidateContract(
                domain=c["domain"],
                key=c["key"],
                name=c["name"],
                aliases=tuple(c.get("aliases", ())),
                supported_operators=tuple(c.get("supported_operators", ())),
                value_type=c.get("value_type", "NUMBER"),
                supported_usage=tuple(c.get("supported_usage", ("filter",))),
                binding_role=c.get("binding_role"),
                preferred_metric=c.get("preferred_metric"),
                score=c.get("score", 1.0),
            )
            for c in state["candidate_pool"]
        ]
        pool = CandidatePool(contracts)
        from app.pipeline.analysis import ConditionPlan, RecallPlan
        plan_data = state["recall_plan"] or {"domains": ["metric"], "conditions": []}
        plan = RecallPlan(
            tuple(plan_data.get("domains", ())),
            tuple(ConditionPlan(c["condition_id"], c["mention"], c["domain"]) for c in plan_data.get("conditions", [])),
        )
        decision = await self._analysis._adjudicator(state["normalized_query"], plan, pool)
        event = _make_trace("candidate_adjudication", t0, {"group_count": len(decision.get("groups", []))})
        return {
            "decision": decision,
            "trace": [*state.get("trace", []), event],
        }

    async def command_build(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        contracts = [
            CandidateContract(
                domain=c["domain"],
                key=c["key"],
                name=c["name"],
                aliases=tuple(c.get("aliases", ())),
                supported_operators=tuple(c.get("supported_operators", ())),
                value_type=c.get("value_type", "NUMBER"),
                supported_usage=tuple(c.get("supported_usage", ("filter",))),
                binding_role=c.get("binding_role"),
                preferred_metric=c.get("preferred_metric"),
                score=c.get("score", 1.0),
            )
            for c in state["candidate_pool"]
        ]
        pool = CandidatePool(contracts)
        dsl = self._builder.build(state["decision"], pool)
        event = _make_trace("command_build", t0, {"limit": dsl.limit, "children_count": len(dsl.children)})
        return {
            "draft_dsl": dsl.model_dump(by_alias=True, exclude_none=True),
            "trace": [*state.get("trace", []), event],
        }

    async def validator(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        dsl = FundScreeningDsl.model_validate(state["draft_dsl"])
        contracts = {
            c["key"]: CandidateContract(
                domain=c["domain"],
                key=c["key"],
                name=c["name"],
                supported_usage=tuple(c.get("supported_usage", ("filter",))),
            )
            for c in state["candidate_pool"]
            if c.get("domain") == "metric"
        }
        res = validate_and_repair(dsl, contracts)
        errors = [{"code": res.clarification.code, "message": res.clarification.message}] if res.clarification else []
        repairs = [{"code": r} for r in res.repairs]
        event = _make_trace("validator", t0, {"errors": len(errors), "repairs": len(repairs)})
        return {
            "draft_dsl": res.dsl.model_dump(by_alias=True, exclude_none=True),
            "validation_errors": errors,
            "repairs": [*state.get("repairs", []), *repairs],
            "repair_attempts": state.get("repair_attempts", 0) + (1 if repairs else 0),
            "trace": [*state.get("trace", []), event],
        }

    async def command_finalize(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        final_dsl = state["draft_dsl"] if not state.get("validation_errors") else None
        event = _make_trace("command_finalize", t0, {"valid": final_dsl is not None})
        return {
            "final_dsl": final_dsl,
            "trace": [*state.get("trace", []), event],
        }
```

- [ ] **Step 4: 运行测试验证通过 (GREEN)**

运行命令:
```powershell
python -m pytest tests/unit/test_graph_nodes.py -v
```
预期输出:
```text
1 passed in 0.25s
```

- [ ] **Step 5: 提交更改**

运行命令:
```bash
git add fund-screening-service/app/graph/nodes.py fund-screening-service/tests/unit/test_graph_nodes.py
git commit -m "feat: encapsulate screening pipeline stages into graph nodes"
```

---

### Task 3: 结构化 LLM (Structured LLM Parsing & Candidate Adjudication)

**Files:**
- Create: `fund-screening-service/app/models/semantic.py`
- Modify: `fund-screening-service/app/retrieval/llm.py`
- Test: `fund-screening-service/tests/unit/test_structured_llm.py`

**Interfaces:**
- Produces:
  - `RecallPlanOutput`: LLM 输出的计划 schema (`conditions: list[ConditionOutput]`)。
  - `DecisionOutput`: LLM 输出的受限裁决 schema (`groups: list[DecisionGroupOutput]`, `limit: int`, `sort: list[SortItemOutput]`)。
  - `validate_against_candidate_contracts(decision: DecisionOutput, contract_index: dict[str, Any]) -> None`: 严格校验 LLM 输出中的 key 必须在候选池内。
- Consumes: `pydantic.BaseModel`, `langchain_core`.

- [ ] **Step 1: 编写失败测试**

创建 `fund-screening-service/tests/unit/test_structured_llm.py`:

```python
import pytest
from pydantic import ValidationError
from app.models.semantic import (
    ConditionOutput,
    DecisionGroupOutput,
    DecisionOutput,
    MetricPredicateOutput,
    RecallPlanOutput,
    validate_against_candidate_contracts,
)


def test_recall_plan_schema_validation() -> None:
    plan = RecallPlanOutput.model_validate({
        "domains": ["metric", "scope"],
        "conditions": [{"condition_id": "c1", "mention": "半导体", "domain": "scope"}],
    })
    assert len(plan.conditions) == 1
    assert plan.conditions[0].mention == "半导体"


def test_decision_output_rejects_arbitrary_operators() -> None:
    with pytest.raises(ValidationError):
        MetricPredicateOutput.model_validate({
            "metric": "f_risk_maxdownside",
            "operator": "CONTAINS",
            "value": 15,
            "unit": "PERCENT",
        })


def test_validate_against_candidate_contracts_rejects_hallucinated_metric() -> None:
    decision = DecisionOutput.model_validate({
        "operator": "AND",
        "groups": [{
            "sectorIds": ["1000019220000000"],
            "metrics": [{"metric": "hallucinated_metric_x", "operator": ">=", "value": 10, "unit": "NUMBER"}],
        }],
        "limit": 20,
    })
    contract_index = {"1000019220000000": {"domain": "scope"}, "f_risk_maxdownside": {"domain": "metric"}}
    with pytest.raises(ValueError, match="hallucinated_metric_x"):
        validate_against_candidate_contracts(decision, contract_index)
```

- [ ] **Step 2: 运行测试验证失败 (RED)**

运行命令:
```powershell
python -m pytest tests/unit/test_structured_llm.py -v
```
预期输出:
```text
ImportError: No module named 'app.models.semantic'
```

- [ ] **Step 3: 编写结构化模型与裁决约束实现**

创建 `fund-screening-service/app/models/semantic.py`:

```python
from __future__ import annotations

from typing import Any, Literal
from pydantic import BaseModel, Field


class ConditionOutput(BaseModel):
    condition_id: str = Field(description="条件唯一标识，如 c1, c2")
    mention: str = Field(description="用户输入的原始提及词")
    domain: Literal["metric", "scope", "entity"] = Field(description="所属领域")


class RecallPlanOutput(BaseModel):
    domains: list[Literal["metric", "scope", "entity"]] = Field(default_factory=list)
    conditions: list[ConditionOutput] = Field(default_factory=list)


class MetricPredicateOutput(BaseModel):
    metric: str
    operator: Literal[">", ">=", "<", "<=", "=", "!="]
    value: Any
    unit: str
    params: list[dict[str, Any]] = Field(default_factory=list)


class EntityRefOutput(BaseModel):
    entity: str
    operator: Literal["=", "!="] = "="


class SortItemOutput(BaseModel):
    metric: str
    direction: Literal["ASC", "DESC"] = "DESC"


class DecisionGroupOutput(BaseModel):
    operator: Literal["AND", "OR", "NOT"] = "AND"
    sectorIds: list[str] = Field(default_factory=list)
    metrics: list[MetricPredicateOutput] = Field(default_factory=list)
    entities: list[EntityRefOutput] = Field(default_factory=list)
    sort: list[SortItemOutput] = Field(default_factory=list)
    limit: int | None = None


class DecisionOutput(BaseModel):
    operator: Literal["AND", "OR", "NOT"] = "AND"
    groups: list[DecisionGroupOutput]
    sort: list[SortItemOutput] = Field(default_factory=list)
    limit: int = 20


def validate_against_candidate_contracts(decision: DecisionOutput, contract_index: dict[str, Any]) -> None:
    for group in decision.groups:
        for sid in group.sectorIds:
            if sid not in contract_index or contract_index[sid].get("domain") != "scope":
                raise ValueError(f"Sector candidate {sid} not in candidate contracts")
        for m in group.metrics:
            if m.metric not in contract_index or contract_index[m.metric].get("domain") != "metric":
                raise ValueError(f"Metric candidate {m.metric} not in candidate contracts")
        for e in group.entities:
            if e.entity not in contract_index or contract_index[e.entity].get("domain") != "entity":
                raise ValueError(f"Entity candidate {e.entity} not in candidate contracts")
    for s in decision.sort:
        if s.metric not in contract_index and s.metric != "fund_code":
            raise ValueError(f"Sort metric candidate {s.metric} not in candidate contracts")
```

更新 `fund-screening-service/app/retrieval/llm.py`，接入 LangChain 结构化输出适配：
```python
from app.models.semantic import DecisionOutput, RecallPlanOutput
```
在 `OpenAiSemanticLlm` 中，若解析失败支持重试 1 次，并在 `adjudicate` 后运行 `validate_against_candidate_contracts`。

- [ ] **Step 4: 运行测试验证通过 (GREEN)**

运行命令:
```powershell
python -m pytest tests/unit/test_structured_llm.py tests/unit/test_llm_semantic.py -v
```
预期输出:
```text
passed in 0.35s
```

- [ ] **Step 5: 提交更改**

运行命令:
```bash
git add fund-screening-service/app/models/semantic.py fund-screening-service/app/retrieval/llm.py fund-screening-service/tests/unit/test_structured_llm.py
git commit -m "feat: implement structured llm outputs and candidate contract validation"
```

---

### Task 4: 三级路由 (Three-Level Route: Direct, Simple, Full)

**Files:**
- Create: `fund-screening-service/app/graph/router.py`
- Modify: `fund-screening-service/app/graph/nodes.py`
- Modify: `fund-screening-service/app/pipeline/router.py`
- Test: `fund-screening-service/tests/unit/test_graph_routing.py`

**Interfaces:**
- Produces:
  - `classify_screening_route(state: FundScreeningState) -> Literal["direct", "simple", "full"]`
  - `simple_semantic_parse(state: FundScreeningState) -> dict[str, Any]` (轻量单步直通节点，跳过复杂召回与裁决)
  - `direct_build(state: FundScreeningState) -> dict[str, Any]` (高置信直通节点，0 次 LLM)
- Consumes: `FundScreeningState`, `preprocess`.

- [ ] **Step 1: 编写失败测试**

创建 `fund-screening-service/tests/unit/test_graph_routing.py`:

```python
from app.graph.router import classify_screening_route
from app.graph.state import empty_state


def test_classify_route_direct_for_exact_match_query() -> None:
    state = empty_state("招商中证白酒指数")
    state["candidate_pool"] = [{"domain": "entity", "key": "161725.OF", "score": 1.0}]
    assert classify_screening_route(state) == "direct"


def test_classify_route_simple_for_single_group_and_conditions() -> None:
    state = empty_state("规模大于10亿且成立超过5年")
    assert classify_screening_route(state) == "simple"


def test_classify_route_full_for_or_not_complex_queries() -> None:
    state1 = empty_state("规模大于10亿或者最大回撤小于10%")
    assert classify_screening_route(state1) == "full"

    state2 = empty_state("排除QDII基金")
    assert classify_screening_route(state2) == "full"
```

- [ ] **Step 2: 运行测试验证失败 (RED)**

运行命令:
```powershell
python -m pytest tests/unit/test_graph_routing.py -v
```
预期输出:
```text
ImportError: No module named 'app.graph.router'
```

- [ ] **Step 3: 编写路由分类器与直通节点实现**

创建 `fund-screening-service/app/graph/router.py`:

```python
from __future__ import annotations

import re
from typing import Literal
from app.graph.state import FundScreeningState


def classify_screening_route(state: FundScreeningState) -> Literal["direct", "simple", "full"]:
    query = state.get("normalized_query") or state.get("query", "")

    # 1. 复杂逻辑判定 -> full
    if any(k in query for k in ("或者", "或", "OR", "排除", "不是", "NOT")):
        return "full"

    # 2. 多业务组特征（分号、逗号隔开的多段独立意图）
    if len(re.split(r"[;；]", query)) > 1:
        return "full"

    # 3. 候选池已有单高置信候选且参数完整 -> direct
    pool = state.get("candidate_pool", [])
    if len(pool) == 1 and pool[0].get("score", 0.0) >= 0.95:
        return "direct"

    # 4. 简单单组且少于 4 个 AND 条件 -> simple
    conditions = re.split(r"[且与和、,，\s]+", query)
    if len([c for c in conditions if c]) <= 3:
        return "simple"

    return "full"
```

在 `fund-screening-service/app/graph/nodes.py` 中追加 `route_classifier`, `direct_build`, `simple_semantic_parse`：
```python
    async def route_classifier(self, state: FundScreeningState) -> dict[str, Any]:
        route = classify_screening_route(state)
        event = _make_trace("route_classifier", time.perf_counter(), {"route": route})
        return {"route": route, "trace": [*state.get("trace", []), event]}

    async def simple_semantic_parse(self, state: FundScreeningState) -> dict[str, Any]:
        # 针对简单单组查询，执行快速解析构建
        t0 = time.perf_counter()
        prep = state["normalized_query"]
        from app.pipeline.mvp import compile_mvp_query
        dsl = compile_mvp_query(prep)
        event = _make_trace("simple_semantic_parse", t0, {"limit": dsl.limit})
        return {
            "draft_dsl": dsl.model_dump(by_alias=True, exclude_none=True),
            "trace": [*state.get("trace", []), event],
        }

    async def direct_build(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        candidate = state["candidate_pool"][0]
        # 直接构建单一指标/实体过滤 DSL
        dsl_dict = {
            "version": "fund-screening/v1",
            "operator": "AND",
            "children": [{
                "sector": {"operator": "OR", "children": [{"sectorId": "1000019220000000", "match": "SELF_OR_DESCENDANT"}]},
                "metric": {"operator": "AND", "children": [{"metric": candidate["key"], "operator": "=", "value": candidate["name"], "unit": candidate.get("value_type", "STRING")}]},
            }],
            "limit": 20,
        }
        event = _make_trace("direct_build", t0, {"candidate": candidate["key"]})
        return {
            "draft_dsl": dsl_dict,
            "trace": [*state.get("trace", []), event],
        }
```

- [ ] **Step 4: 运行测试验证通过 (GREEN)**

运行命令:
```powershell
python -m pytest tests/unit/test_graph_routing.py -v
```
预期输出:
```text
3 passed in 0.20s
```

- [ ] **Step 5: 提交更改**

运行命令:
```bash
git add fund-screening-service/app/graph/router.py fund-screening-service/app/graph/nodes.py fund-screening-service/tests/unit/test_graph_routing.py
git commit -m "feat: implement three-level deterministic routing in screening graph"
```

---

### Task 5: 校验循环 (Validation & Mechanical Repair Loop with Max Retry Limit)

**Files:**
- Create: `fund-screening-service/app/graph/repair.py`
- Modify: `fund-screening-service/app/graph/nodes.py`
- Modify: `fund-screening-service/app/pipeline/validator.py`
- Test: `fund-screening-service/tests/unit/test_graph_repair_loop.py`

**Interfaces:**
- Produces:
  - `decide_validation_outcome(state: FundScreeningState) -> Literal["finalize", "repair", "clarify"]`
  - `mechanical_repair_node(state: FundScreeningState) -> dict[str, Any]` (仅执行注册过的修复策略，0 次 LLM)
  - 严格上限：`state["repair_attempts"] >= 2` 时强制转向 `clarify`。
- Consumes: `FundScreeningState`, `validate_and_repair`.

- [ ] **Step 1: 编写失败测试**

创建 `fund-screening-service/tests/unit/test_graph_repair_loop.py`:

```python
import pytest
from app.graph.repair import decide_validation_outcome, mechanical_repair_node
from app.graph.state import empty_state


def test_decide_outcome_valid_leads_to_finalize() -> None:
    state = empty_state("成立大于5年")
    state["draft_dsl"] = {"version": "fund-screening/v1", "operator": "AND", "children": [], "limit": 20}
    state["validation_errors"] = []
    assert decide_validation_outcome(state) == "finalize"


def test_decide_outcome_repairable_within_limit_leads_to_repair() -> None:
    state = empty_state("回撤小于10%")
    state["draft_dsl"] = {"version": "fund-screening/v1"}
    state["validation_errors"] = [{"code": "NEGATIVE_DRAWDOWN_STORAGE", "message": "修复负值回撤"}]
    state["repair_attempts"] = 0
    assert decide_validation_outcome(state) == "repair"


def test_decide_outcome_exhausted_repairs_leads_to_clarify() -> None:
    state = empty_state("测试连续坏输入")
    state["draft_dsl"] = {"version": "fund-screening/v1"}
    state["validation_errors"] = [{"code": "LIMIT_TOO_LARGE", "message": "数量超标"}]
    state["repair_attempts"] = 2  # 已达上限 2
    assert decide_validation_outcome(state) == "clarify"


@pytest.mark.asyncio
async def test_mechanical_repair_applies_negative_drawdown_repair() -> None:
    state = empty_state("回撤小于15%")
    state["draft_dsl"] = {
        "version": "fund-screening/v1",
        "operator": "AND",
        "children": [{
            "sector": {"operator": "OR", "children": [{"sectorId": "1000019220000000", "match": "SELF_OR_DESCENDANT"}]},
            "metric": {"operator": "AND", "children": [{"metric": "f_risk_maxdownside", "operator": "<", "value": 15, "unit": "PERCENT"}]},
        }],
        "limit": 20,
    }
    state["repair_attempts"] = 0
    update = await mechanical_repair_node(state)
    assert update["repair_attempts"] == 1
    child = update["draft_dsl"]["children"][0]["metric"]["children"][0]
    assert child["operator"] == ">="
    assert child["value"] == -15
```

- [ ] **Step 2: 运行测试验证失败 (RED)**

运行命令:
```powershell
python -m pytest tests/unit/test_graph_repair_loop.py -v
```
预期输出:
```text
ImportError: No module named 'app.graph.repair'
```

- [ ] **Step 3: 编写循环决策与机械修复实现**

创建 `fund-screening-service/app/graph/repair.py`:

```python
from __future__ import annotations

import time
from typing import Any, Literal
from app.graph.nodes import _make_trace
from app.graph.state import FundScreeningState
from app.models.dsl import FundScreeningDsl
from app.pipeline.validator import validate_and_repair


def decide_validation_outcome(state: FundScreeningState) -> Literal["finalize", "repair", "clarify"]:
    errors = state.get("validation_errors", [])
    if not errors:
        return "finalize"
    attempts = state.get("repair_attempts", 0)
    if attempts >= 2:
        return "clarify"
    # 可自动修复的 code 列表
    repairable_codes = {"NEGATIVE_DRAWDOWN_STORAGE", "DEFAULT_GROUP_SORT", "DEFAULT_SECTOR_REQUIRED"}
    if all(e.get("code") in repairable_codes for e in errors):
        return "repair"
    return "clarify"


async def mechanical_repair_node(state: FundScreeningState) -> dict[str, Any]:
    t0 = time.perf_counter()
    dsl = FundScreeningDsl.model_validate(state["draft_dsl"])
    contracts = {
        c["key"]: c for c in state.get("candidate_pool", []) if c.get("domain") == "metric"
    }
    # 重新执行领域层 validate_and_repair 规范化
    repaired_res = validate_and_repair(dsl, contracts)
    attempts = state.get("repair_attempts", 0) + 1
    event = _make_trace("mechanical_repair", t0, {"attempt": attempts, "repairs": list(repaired_res.repairs)})
    return {
        "draft_dsl": repaired_res.dsl.model_dump(by_alias=True, exclude_none=True),
        "repairs": [*state.get("repairs", []), *[{"code": r} for r in repaired_res.repairs]],
        "repair_attempts": attempts,
        "trace": [*state.get("trace", []), event],
    }


async def clarification_node(state: FundScreeningState) -> dict[str, Any]:
    t0 = time.perf_counter()
    errors = state.get("validation_errors", [])
    code = errors[0]["code"] if errors else "VALIDATION_REPAIR_EXHAUSTED"
    message = errors[0]["message"] if errors else "筛选条件校验修复次数超限，请明确具体指标或参数。"
    clarification = {
        "code": code,
        "message": message,
        "suggestions": ["调整指标阈值", "检查排序指标是否支持"],
    }
    event = _make_trace("clarification", t0, {"clarification_code": code})
    return {
        "clarification": clarification,
        "final_dsl": None,
        "trace": [*state.get("trace", []), event],
    }
```

- [ ] **Step 4: 运行测试验证通过 (GREEN)**

运行命令:
```powershell
python -m pytest tests/unit/test_graph_repair_loop.py -v
```
预期输出:
```text
4 passed in 0.22s
```

- [ ] **Step 5: 提交更改**

运行命令:
```bash
git add fund-screening-service/app/graph/repair.py fund-screening-service/tests/unit/test_graph_repair_loop.py
git commit -m "feat: implement bounded validation repair loop with clarification"
```

---

### Task 6: 多轮 Checkpoint 与上下文恢复 (Multi-turn Checkpoint & Edit Branching)

**Files:**
- Create: `fund-screening-service/app/graph/checkpoint.py`
- Modify: `fund-screening-service/app/config.py`
- Modify: `fund-screening-service/app/graph/nodes.py`
- Test: `fund-screening-service/tests/unit/test_graph_checkpoint.py`

**Interfaces:**
- Produces:
  - `build_checkpointer(redis_url: str | None = None) -> BaseCheckpointSaver`: 默认内存 `MemorySaver`，配置 Redis 时启用 `RedisSaver`。
  - `make_graph_config(conversation_id: str | None) -> dict[str, Any]`: 生成标准 `{"configurable": {"thread_id": conversation_id}}`。
  - 多轮编辑支持：`previous_dsl` 显式优先，简单编辑（limit/sort）跳过全量召回。
- Consumes: `langgraph.checkpoint.base`, `langgraph.checkpoint.memory.MemorySaver`.

- [ ] **Step 1: 编写失败测试**

创建 `fund-screening-service/tests/unit/test_graph_checkpoint.py`:

```python
import pytest
from app.graph.checkpoint import build_checkpointer, make_graph_config
from app.graph.state import empty_state
from langgraph.checkpoint.memory import MemorySaver


def test_build_checkpointer_returns_memory_saver_by_default() -> None:
    saver = build_checkpointer(None)
    assert isinstance(saver, MemorySaver)


def test_make_graph_config_sets_thread_id() -> None:
    config = make_graph_config("session-999")
    assert config["configurable"]["thread_id"] == "session-999"


def test_make_graph_config_fallback_when_none() -> None:
    config = make_graph_config(None)
    assert "thread_id" in config["configurable"]
```

- [ ] **Step 2: 运行测试验证失败 (RED)**

运行命令:
```powershell
python -m pytest tests/unit/test_graph_checkpoint.py -v
```
预期输出:
```text
ImportError: No module named 'app.graph.checkpoint'
```

- [ ] **Step 3: 编写 Checkpoint 构造与配置实现**

创建 `fund-screening-service/app/graph/checkpoint.py`:

```python
from __future__ import annotations

import uuid
from typing import Any
from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.checkpoint.memory import MemorySaver


def build_checkpointer(redis_url: str | None = None) -> BaseCheckpointSaver:
    if redis_url:
        try:
            from langgraph_checkpoint_redis import RedisSaver  # type: ignore[import-not-found]
            return RedisSaver.from_conn_string(redis_url)
        except Exception:
            return MemorySaver()
    return MemorySaver()


def make_graph_config(conversation_id: str | None) -> dict[str, Any]:
    thread_id = conversation_id if conversation_id else str(uuid.uuid4())
    return {"configurable": {"thread_id": thread_id}}
```

在 `fund-screening-service/app/config.py` 的 `Settings` 中增加 `redis_url`:
```python
redis_url: str = ""
```

- [ ] **Step 4: 运行测试验证通过 (GREEN)**

运行命令:
```powershell
python -m pytest tests/unit/test_graph_checkpoint.py -v
```
预期输出:
```text
3 passed in 0.18s
```

- [ ] **Step 5: 提交更改**

运行命令:
```bash
git add fund-screening-service/app/graph/checkpoint.py fund-screening-service/app/config.py fund-screening-service/tests/unit/test_graph_checkpoint.py
git commit -m "feat: implement checkpointing and config generation for multi-turn conversations"
```

---

### Task 7: HTTP/WebSocket 事件统一与 Graph 统一接入 (Unified HTTP/WebSocket Event Streaming & Production Cutover)

**Files:**
- Create: `fund-screening-service/app/graph/workflow.py`
- Create: `fund-screening-service/app/graph/compiler.py`
- Modify: `fund-screening-service/app/pipeline/orchestrator.py`
- Modify: `fund-screening-service/app/api/commands.py`
- Modify: `fund-screening-service/app/main.py`
- Modify: `fund-screening-service/app/runtime.py`
- Test: `fund-screening-service/tests/api/test_graph_api.py`

**Interfaces:**
- Produces:
  - `build_screening_graph(nodes: ScreeningGraphNodes, checkpointer=None) -> CompiledGraph`: 组装带三级路由、循环修复、Clarification 的完整图。
  - `LangGraphCommandCompiler(graph)`: 保持 `compile(query, previous_dsl=None, conversation_id=None)` 返回 `CompileResult`。
  - HTTP POST `/v1/fund-screening/commands`: 内部直接调用 `compiled_graph.ainvoke(...)`。
  - WebSocket `/v1/fund-screening/ws`: 内部直接调用 `compiled_graph.astream(..., stream_mode="updates")`，每个更新阶段发射 `stage` 事件，最终发射 `finalized` / `final`。
- Consumes: `ScreeningGraphNodes`, `FundScreeningState`, `build_checkpointer`.

- [ ] **Step 1: 编写失败测试**

创建 `fund-screening-service/tests/api/test_graph_api.py`:

```python
from fastapi.testclient import TestClient
from app.main import create_app


def test_http_and_ws_reach_same_dsl_and_stream_events() -> None:
    client = TestClient(create_app())
    query = "筛选最大回撤低于15%、规模大于10亿的主动权益基金，按近3年收益降序取前20只"

    # HTTP 请求
    http_resp = client.post("/v1/fund-screening/commands", json={"query": query})
    assert http_resp.status_code == 200
    http_dsl = http_resp.json()["dsl"]

    # WebSocket 请求
    with client.websocket_connect("/v1/fund-screening/ws") as ws:
        ws.send_json({"query": query})
        events = []
        for _ in range(20):
            try:
                ev = ws.receive_json()
                events.append(ev)
                if ev.get("type") in ("finalized", "final"):
                    break
            except Exception:
                break

    stages = [e["stage"] for e in events if e.get("type") in ("stage", "stage_completed")]
    assert len(stages) >= 3
    final_event = events[-1]
    assert final_event["dsl"] == http_dsl
```

- [ ] **Step 2: 运行测试验证失败 (RED)**

运行命令:
```powershell
python -m pytest tests/api/test_graph_api.py -v
```
预期输出:
```text
ImportError or AssertionError: 阶段流与单图一致性校验未达成
```

- [ ] **Step 3: 组装 StateGraph 与适配器接入**

创建 `fund-screening-service/app/graph/workflow.py`:

```python
from __future__ import annotations

from app.graph.nodes import ScreeningGraphNodes
from app.graph.repair import clarification_node, decide_validation_outcome, mechanical_repair_node
from app.graph.state import FundScreeningState
from langgraph.graph import END, START, StateGraph


def build_screening_graph(nodes: ScreeningGraphNodes, checkpointer=None):
    builder = StateGraph(FundScreeningState)

    # 1. 注册基础节点
    builder.add_node("query_preprocess", nodes.query_preprocess)
    builder.add_node("route_classifier", nodes.route_classifier)
    builder.add_node("direct_build", nodes.direct_build)
    builder.add_node("simple_semantic_parse", nodes.simple_semantic_parse)
    builder.add_node("pre_recall_plan", nodes.pre_recall_plan)
    builder.add_node("parallel_recall", nodes.parallel_recall)
    builder.add_node("candidate_adjudication", nodes.candidate_adjudication)
    builder.add_node("command_build", nodes.command_build)
    builder.add_node("validator", nodes.validator)
    builder.add_node("mechanical_repair", mechanical_repair_node)
    builder.add_node("clarification", clarification_node)
    builder.add_node("command_finalize", nodes.command_finalize)

    # 2. 连接起始与预处理
    builder.add_edge(START, "query_preprocess")
    builder.add_edge("query_preprocess", "route_classifier")

    # 3. 三级条件路由
    def route_condition(state: FundScreeningState) -> str:
        r = state.get("route", "full")
        if r == "direct":
            return "direct_build"
        if r == "simple":
            return "simple_semantic_parse"
        return "pre_recall_plan"

    builder.add_conditional_edges(
        "route_classifier",
        route_condition,
        {
            "direct_build": "direct_build",
            "simple_semantic_parse": "simple_semantic_parse",
            "pre_recall_plan": "pre_recall_plan",
        },
    )

    # 4. full 路径串联
    builder.add_edge("pre_recall_plan", "parallel_recall")
    builder.add_edge("parallel_recall", "candidate_adjudication")
    builder.add_edge("candidate_adjudication", "command_build")

    # 5. 各分支汇入 validator
    builder.add_edge("direct_build", "validator")
    builder.add_edge("simple_semantic_parse", "validator")
    builder.add_edge("command_build", "validator")

    # 6. 校验与循环修复条件边
    def validation_condition(state: FundScreeningState) -> str:
        res = decide_validation_outcome(state)
        if res == "finalize":
            return "command_finalize"
        if res == "repair":
            return "mechanical_repair"
        return "clarification"

    builder.add_conditional_edges(
        "validator",
        validation_condition,
        {
            "command_finalize": "command_finalize",
            "mechanical_repair": "mechanical_repair",
            "clarification": "clarification",
        },
    )
    builder.add_edge("mechanical_repair", "validator")

    # 7. 终态边
    builder.add_edge("clarification", END)
    builder.add_edge("command_finalize", END)

    return builder.compile(checkpointer=checkpointer)
```

创建 `fund-screening-service/app/graph/compiler.py`:

```python
from __future__ import annotations

from typing import Any
from app.graph.checkpoint import make_graph_config
from app.graph.state import empty_state
from app.models.dsl import FundScreeningDsl
from app.pipeline.compiler import CompileResult
from app.pipeline.validator import ValidationResult


class LangGraphCommandCompiler:
    def __init__(self, graph: Any) -> None:
        self.graph = graph

    async def compile(
        self,
        query: str,
        previous_dsl: dict | None = None,
        conversation_id: str | None = None,
    ) -> CompileResult:
        state = empty_state(query, conversation_id=conversation_id, previous_dsl=previous_dsl)
        config = make_graph_config(conversation_id)
        result = await self.graph.ainvoke(state, config=config)
        final_dsl = FundScreeningDsl.model_validate(result["final_dsl"] or result["draft_dsl"])
        trace = {
            "route": result.get("route", "full"),
            "condition_count": len(result.get("recall_plan", {}).get("conditions", []) if result.get("recall_plan") else []),
            "candidate_count": len(result.get("candidate_pool", [])),
            "repairs": [r.get("code") for r in result.get("repairs", [])],
        }
        return CompileResult(final_dsl, trace, ValidationResult(final_dsl))
```

更新 `fund-screening-service/app/pipeline/orchestrator.py`、`app/api/commands.py`、`app/main.py` 和 `app/runtime.py`，使之统一持有 Graph 并在 HTTP/WS 中调用同一个 Graph 实例。

- [ ] **Step 4: 运行测试验证通过 (GREEN)**

运行命令:
```powershell
python -m pytest tests/api tests/unit -v
```
预期输出:
```text
All API tests and unit tests passed!
```

- [ ] **Step 5: 提交更改**

运行命令:
```bash
git add fund-screening-service/app/graph/workflow.py fund-screening-service/app/graph/compiler.py fund-screening-service/app/pipeline/orchestrator.py fund-screening-service/app/api/commands.py fund-screening-service/app/main.py fund-screening-service/app/runtime.py fund-screening-service/tests/api/test_graph_api.py
git commit -m "feat: unify http and websocket execution on compiled langgraph"
```

---

### Task 8: 指标参数目录与校验 (Metric Parameter Catalog Extension & Function Signature Validation)

**Files:**
- Create: `fund-screening-service/app/models/parameters.py`
- Create: `fund-screening-service/app/catalog/parameter_rules.py`
- Modify: `fund-screening-service/app/models/candidate.py`
- Modify: `fund-screening-service/app/models/dsl.py`
- Modify: `fund-screening-service/app/catalog/loader.py`
- Modify: `fund-screening-service/app/pipeline/validator.py`
- Modify: `fund-screening-service/tests/fixtures/metrics-small.json`
- Test: `fund-screening-service/tests/unit/test_metric_parameter_validation.py`
- Test: `fund-screening-service/tests/unit/test_parameter_rules.py`

**Interfaces:**
- Produces:
  - `MetricParameterDefinition`: 描述参数签名（`key`, `value_type`, `required`, `default_value`, `options`）。
  - `MetricParameterValue`: 运行时表达式参数项（`key`, `value`）。
  - `infer_parameters(metric: dict) -> list[dict]`: 169 个指标的规则推导函数。
  - `validate_metric_parameters(metric_name, params, contract) -> tuple[list[str], list[dict]]`: 校验必填、类型、枚举选项、日期起止关系。
  - 特殊豁免：`windCode` / `FUND_CODE` 作为引擎上下文绑定参数，允许在用户筛选 DSL 中缺省。
- Consumes: `CandidateContract`, `MetricExpression`.

- [ ] **Step 1: 编写失败测试**

创建 `fund-screening-service/tests/unit/test_metric_parameter_validation.py`:

```python
from app.models.parameters import MetricParameterDefinition, MetricParameterValue
from app.pipeline.validator import validate_metric_parameters


def test_validator_fills_default_trade_date() -> None:
    param_def = MetricParameterDefinition(
        key="endDate", valueType="DATE", required=True, defaultValue="LATEST_TRADE_DATE"
    )
    errors, filled = validate_metric_parameters("f_return_1y", [], [param_def])
    assert errors == []
    assert len(filled) == 1
    assert filled[0]["key"] == "endDate"
    assert filled[0]["value"] == "LATEST_TRADE_DATE"


def test_validator_rejects_invalid_enum_option() -> None:
    param_def = MetricParameterDefinition(
        key="frequency", valueType="ENUM", required=False, defaultValue="DAILY",
        options=[{"label": "日频", "value": "DAILY"}, {"label": "月频", "value": "MONTHLY"}],
    )
    errors, filled = validate_metric_parameters("f_nav_return", [{"key": "frequency", "value": "HOURLY"}], [param_def])
    assert any("HOURLY" in err for err in errors)


def test_validator_rejects_reversed_dates() -> None:
    p1 = MetricParameterDefinition(key="startDate", valueType="DATE", required=True)
    p2 = MetricParameterDefinition(key="endDate", valueType="DATE", required=True)
    input_params = [{"key": "startDate", "value": "2026-09-28"}, {"key": "endDate", "value": "2023-09-28"}]
    errors, filled = validate_metric_parameters("f_risk_alpha", input_params, [p1, p2])
    assert any("startDate must be <= endDate" in err for err in errors)


def test_validator_exempts_context_bound_windcode() -> None:
    p_wind = MetricParameterDefinition(key="windCode", valueType="FUND_CODE", required=True)
    errors, filled = validate_metric_parameters("f_risk_alpha", [], [p_wind])
    assert errors == []
```

创建 `fund-screening-service/tests/unit/test_parameter_rules.py`:

```python
import pytest
from app.catalog.parameter_rules import infer_parameters


@pytest.mark.parametrize(("mnemonic", "expected_keys"), [
    ("f_info_fullname", ["windCode"]),
    ("f_return_3y", ["windCode", "endDate"]),
    ("f_risk_maxdownside", ["windCode", "startDate", "endDate"]),
    ("f_risk_alpha", ["windCode", "startDate", "endDate", "indexCode"]),
    ("f_prt_stocktonav", ["windCode", "reportDate"]),
])
def test_infer_parameters_by_metric_family(mnemonic: str, expected_keys: list[str]) -> None:
    keys = [p["key"] for p in infer_parameters({"mnemonic": mnemonic})]
    assert keys == expected_keys
```

- [ ] **Step 2: 运行测试验证失败 (RED)**

运行命令:
```powershell
python -m pytest tests/unit/test_metric_parameter_validation.py tests/unit/test_parameter_rules.py -v
```
预期输出:
```text
ImportError: No module named 'app.models.parameters'
```

- [ ] **Step 3: 编写指标参数模型、规则推导与校验逻辑**

创建 `fund-screening-service/app/models/parameters.py`:

```python
from __future__ import annotations

from typing import Any, Literal
from pydantic import BaseModel, ConfigDict, Field


class ParameterOption(BaseModel):
    label: str
    value: str


class MetricParameterDefinition(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    key: str
    value_type: Literal["STRING", "NUMBER", "INTEGER", "BOOLEAN", "DATE", "FUND_CODE", "INDEX_CODE", "SECTOR_CODE", "ENUM"] = Field(alias="valueType")
    required: bool = False
    default_value: Any | None = Field(default=None, alias="defaultValue")
    options: list[ParameterOption] = Field(default_factory=list)


class MetricParameterValue(BaseModel):
    key: str
    value: Any
```

创建 `fund-screening-service/app/catalog/parameter_rules.py`:

```python
from __future__ import annotations

from typing import Any


def infer_parameters(metric: dict[str, Any]) -> list[dict[str, Any]]:
    m = metric.get("mnemonic", "")
    params: list[dict[str, Any]] = [
        {"key": "windCode", "valueType": "FUND_CODE", "required": True}
    ]
    if m in ("f_risk_alpha", "f_risk_beta", "f_risk_treynor", "f_risk_sharpe"):
        params.extend([
            {"key": "startDate", "valueType": "DATE", "required": True},
            {"key": "endDate", "valueType": "DATE", "required": True, "defaultValue": "LATEST_TRADE_DATE"},
            {"key": "indexCode", "valueType": "INDEX_CODE", "required": False, "defaultValue": "FUND_BENCHMARK"},
        ])
    elif m in ("f_risk_maxdownside", "f_risk_downsidedeviation", "f_risk_trackingerror"):
        params.extend([
            {"key": "startDate", "valueType": "DATE", "required": True},
            {"key": "endDate", "valueType": "DATE", "required": True, "defaultValue": "LATEST_TRADE_DATE"},
        ])
    elif "return" in m or "nav" in m:
        params.append({"key": "endDate", "valueType": "DATE", "required": True, "defaultValue": "LATEST_TRADE_DATE"})
    elif "prt" in m or "holder" in m or "style" in m:
        params.append({"key": "reportDate", "valueType": "DATE", "required": True, "defaultValue": "LATEST_REPORT_DATE"})
    return params
```

在 `fund-screening-service/app/pipeline/validator.py` 中实现 `validate_metric_parameters`，并在 `validate_and_repair` 中遍历指标叶子节点执行参数签名与值校验。

- [ ] **Step 4: 运行测试验证通过 (GREEN) 与全量回归**

运行命令:
```powershell
python -m pytest tests/unit/test_metric_parameter_validation.py tests/unit/test_parameter_rules.py tests -v
```
预期输出:
```text
All unit and API tests passed!
```

- [ ] **Step 5: 提交更改**

运行命令:
```bash
git add fund-screening-service/app/models/parameters.py fund-screening-service/app/catalog/parameter_rules.py fund-screening-service/app/models/candidate.py fund-screening-service/app/models/dsl.py fund-screening-service/app/catalog/loader.py fund-screening-service/app/pipeline/validator.py fund-screening-service/tests/fixtures/metrics-small.json fund-screening-service/tests/unit/test_metric_parameter_validation.py fund-screening-service/tests/unit/test_parameter_rules.py
git commit -m "feat: support metric function parameter catalog contract and validation"
```

---

## Plan Review & Verification Checkpoints

1. **Spec Coverage Check:**
   - [x] Graph State TypedDict 对齐（第 3 节）-> Task 1
   - [x] 节点职责划分与包装（第 5 节）-> Task 2
   - [x] LangChain 结构化输出与受限裁决（第 5.3, 5.5 节）-> Task 3
   - [x] direct / simple / full 三级路由分支（第 4, 5.2 节）-> Task 4
   - [x] 校验循环上限 2 次与 Clarification（第 4, 5.7 节）-> Task 5
   - [x] 多轮 Checkpoint 与会话 thread_id（第 7 节）-> Task 6
   - [x] 单 Graph 编译与 HTTP/WebSocket 事件流统一（第 8, 10 节）-> Task 7
   - [x] 指标参数定义 parameters 与运行时 params 校验（第 6 节）-> Task 8
2. **Zero Production Mutation in Planning Step:**
   - 当前步骤仅生成计划文件，生产代码与现有 34 个测试均未做任何更改。
3. **Execution Readiness:**
   - 包含具体文件路径、输入输出契约、完整测试用例代码、验证命令与提交信息。
