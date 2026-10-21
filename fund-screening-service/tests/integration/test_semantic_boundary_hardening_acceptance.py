from __future__ import annotations

import pytest
from app.graph.checkpoint import make_graph_config
from app.graph.state import empty_state
from app.main import _build_default_graph
from app.models.candidate import CandidateContract
from app.models.semantic_plan import (
    AdjudicationResult,
    MetricLeaf,
    MetricTree,
    PlanGroup,
    ResolvedCondition,
    SemanticPlan,
)
from app.pipeline.builder import CommandBuilder
from app.pipeline.intent_funnel import IntentFunnel, IntentType


@pytest.mark.asyncio
async def test_criterion_1_intent_and_routing_are_independent():
    funnel = IntentFunnel()
    decision = funnel.classify_intent("筛选近一年收益大于10%的基金")
    assert decision.intent == IntentType.CREATE
    # Intent decision has no route property
    assert not hasattr(decision, "route")


@pytest.mark.asyncio
async def test_criterion_2_non_provable_queries_not_consumed_by_strict_direct():
    graph = _build_default_graph()
    # Fuzzy query must not be consumed by direct probe
    state = empty_state("找一只收益好的稳健基金")
    config = make_graph_config()
    final_state = state
    async for update in graph.astream(state, config=config, stream_mode="updates"):
        for _, u in update.items():
            final_state = {**final_state, **u}

    direct_event = next((t for t in final_state.get("trace", []) if t["stage"] == "strict_direct_probe"), None)
    if direct_event:
        assert direct_event["summary"].get("route") == "semantic_plan"


@pytest.mark.asyncio
async def test_criterion_3_builder_stable_dsl_unaffected_by_adjudication_order():
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
            )
        ]
    )
    # Even if adjudication bindings arrive in reverse order
    adj_1 = AdjudicationResult(
        condition_bindings=[
            ResolvedCondition(conditionId="c1", selectedCandidateId="f_scale", operator=">", value=10.0),
            ResolvedCondition(conditionId="c2", selectedCandidateId="f_year", operator=">", value=5.0),
        ]
    )
    adj_2 = AdjudicationResult(
        condition_bindings=[
            ResolvedCondition(conditionId="c2", selectedCandidateId="f_year", operator=">", value=5.0),
            ResolvedCondition(conditionId="c1", selectedCandidateId="f_scale", operator=">", value=10.0),
        ]
    )
    contracts = {
        "f_scale": CandidateContract("metric", "f_scale", "规模"),
        "f_year": CandidateContract("metric", "f_year", "成立年限"),
        "1000019220000000": CandidateContract("scope", "1000019220000000", "全市场"),
    }
    builder = CommandBuilder()
    dsl1 = builder.build_from_plan(plan, adj_1, contracts)
    dsl2 = builder.build_from_plan(plan, adj_2, contracts)

    assert dsl1.model_dump() == dsl2.model_dump()


@pytest.mark.asyncio
async def test_criterion_4_unrepairable_dsl_returns_clarification_plan():
    from app.pipeline.validator import validate_and_repair
    from app.models.dsl import FundScreeningDsl, BusinessGroup, SectorExpression, SectorLeaf
    from app.models.dsl_nodes import MetricLogicNode, MetricPredicate

    # Query with unsupported operator != on fund scale
    dsl = FundScreeningDsl(
        version="fund-screening/v1",
        operator="AND",
        children=[
            BusinessGroup(
                sector=SectorExpression(operator="OR", children=[SectorLeaf(sectorId="1000019220000000")]),
                metric=MetricLogicNode(
                    operator="AND",
                    children=[
                        MetricPredicate(metric="f_scale", operator="!=", value=10.0, unit="亿")
                    ],
                ),
            )
        ],
        limit=20,
    )
    contracts = {
        "f_scale": CandidateContract("metric", "f_scale", "规模", supported_operators=(">", ">=", "<", "<="))
    }
    res = validate_and_repair(dsl, contracts)
    assert res.status == "REJECT_OR_CLARIFY"
    assert res.clarification_plan is not None
    assert res.clarification_plan.code == "UNSUPPORTED_OPERATOR"


@pytest.mark.asyncio
async def test_criterion_5_strict_direct_fund_code_bypasses_semantic_plan():
    graph = _build_default_graph()
    state = empty_state("001938")
    config = make_graph_config()
    final_state = state
    async for update in graph.astream(state, config=config, stream_mode="updates"):
        for _, u in update.items():
            final_state = {**final_state, **u}

    assert final_state.get("final_dsl") is not None
    trace_stages = [t["stage"] for t in final_state.get("trace", [])]
    assert "strict_direct_probe" in trace_stages
    assert "semantic_plan" not in trace_stages
