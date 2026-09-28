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
