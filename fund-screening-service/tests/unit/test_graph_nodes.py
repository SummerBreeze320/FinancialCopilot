import pytest
from app.graph.nodes import ScreeningGraphNodes
from app.graph.state import empty_state
from app.models.candidate import CandidateContract
from app.pipeline.analysis import AnalysisService, CandidatePool, ConditionPlan, RecallPlan
from app.pipeline.builder import CommandBuilder
from app.graph.workflow import build_screening_graph



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


@pytest.mark.asyncio
async def test_direct_build_rejects_empty_candidate_pool() -> None:
    nodes = ScreeningGraphNodes(analysis=AnalysisService(lambda q: RecallPlan(("metric",)), FakeRetriever(), FakeRetriever(), FakeRetriever()))
    state = empty_state("招商中证白酒指数")
    # candidate_pool is empty
    with pytest.raises((ValueError, RuntimeError), match="(?i)candidate"):
        await nodes.direct_build(state)


@pytest.mark.asyncio
async def test_simple_semantic_parse_compiles_fundscale_without_compile_mvp_query() -> None:
    analysis = AnalysisService(
        plan_generator=lambda q: RecallPlan(("metric",)),
        metric_retriever=FakeRetriever(),
        scope_retriever=FakeRetriever(),
        entity_retriever=FakeRetriever(),
    )
    nodes = ScreeningGraphNodes(analysis=analysis, builder=CommandBuilder())
    state = empty_state("基金规模大于10亿")
    state["candidate_pool"] = [{
        "domain": "metric",
        "key": "f_info_fundscale",
        "name": "基金规模",
        "aliases": ["规模", "基金规模"],
        "supported_operators": [">", ">=", "<", "<="],
        "value_type": "BILLION_CNY",
        "supported_usage": ["filter", "sort"],
        "score": 1.0,
    }]
    state["candidate_contract_index"] = {"f_info_fundscale": state["candidate_pool"][0]}

    update = await nodes.simple_semantic_parse(state)
    assert update["draft_dsl"] is not None
    leaf = update["draft_dsl"]["children"][0]["metric"]["children"][0]
    assert leaf["metric"] == "f_info_fundscale"
    assert leaf["operator"] == ">"
    assert leaf["value"] == 10
    assert leaf["unit"] == "BILLION_CNY"


@pytest.mark.asyncio
async def test_full_route_exactly_two_llm_calls_and_simple_at_most_one() -> None:
    plan_llm_calls = 0
    adjudicate_llm_calls = 0

    async def mock_plan(q):
        nonlocal plan_llm_calls
        plan_llm_calls += 1
        return RecallPlan(("metric",), (ConditionPlan("c1", "最大回撤", "metric"),))

    async def mock_adjudicate(q, p, pool):
        nonlocal adjudicate_llm_calls
        adjudicate_llm_calls += 1
        return {
            "groups": [{
                "sectorIds": ["1000019220000000"],
                "metrics": [{"metric": "f_risk_maxdownside", "operator": "<", "value": 15, "unit": "PERCENT"}],
            }],
            "limit": 20,
        }

    analysis = AnalysisService(
        plan_generator=mock_plan,
        metric_retriever=FakeRetriever(),
        scope_retriever=FakeRetriever(),
        entity_retriever=FakeRetriever(),
        adjudicator=mock_adjudicate,
    )
    nodes = ScreeningGraphNodes(analysis, CommandBuilder())
    graph = build_screening_graph(nodes)

    # 1. Full route test: query with '或者'
    full_state = empty_state("规模大于10亿或者最大回撤小于10%")
    await graph.ainvoke(full_state)
    assert plan_llm_calls == 1
    assert adjudicate_llm_calls == 1

    # 2. Simple route test: simple query
    plan_llm_calls = 0
    adjudicate_llm_calls = 0
    simple_state = empty_state("基金规模大于10亿")
    await graph.ainvoke(simple_state)
    assert plan_llm_calls == 0
    assert adjudicate_llm_calls <= 1

