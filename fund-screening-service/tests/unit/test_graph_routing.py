import pytest
from app.graph.router import classify_screening_route
from app.graph.state import empty_state





def test_classify_route_direct_for_exact_match_candidate() -> None:
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


@pytest.mark.asyncio
async def test_end_to_end_direct_route_makes_zero_llm_calls() -> None:
    from app.models.candidate import CandidateContract
    from app.pipeline.analysis import AnalysisService, RecallPlan
    from app.graph.nodes import ScreeningGraphNodes
    from app.graph.workflow import build_screening_graph

    class CallTrackingLlm:
        def __init__(self):
            self.calls = []
        async def generate_plan(self, q):
            self.calls.append(("plan", q))
            return RecallPlan(("metric",))
        async def adjudicate(self, q, p, pool):
            self.calls.append(("adjudicate", q))
            return {"groups": [], "limit": 20}

    llm = CallTrackingLlm()

    class LexicalEntityMock:
        async def search(self, text: str, top_k: int):
            if "001938" in text or "圆信永丰" in text:
                return [CandidateContract(
                    domain="entity",
                    key="001938.OF",
                    name="圆信永丰双红利A",
                    aliases=("001938",),
                    supported_operators=("=", "!="),
                    value_type="STRING",
                    preferred_metric="f_info_fundcode",
                    score=1.0,
                )]
            return []

    class EmptyRetriever:
        async def search(self, text: str, top_k: int):
            return []

    analysis = AnalysisService(
        llm.generate_plan,
        EmptyRetriever(),
        EmptyRetriever(),
        LexicalEntityMock(),
        llm.adjudicate,
    )
    nodes = ScreeningGraphNodes(analysis)
    graph = build_screening_graph(nodes)

    result = await graph.ainvoke(empty_state("001938"))
    assert result["route"] == "direct"
    assert result["final_dsl"] is not None
    assert len(llm.calls) == 0  # 0 LLM calls for direct route!
    child_metric = result["final_dsl"]["children"][0]["metric"]["children"][0]
    assert child_metric["value"] == "001938.OF" or child_metric["value"] == "001938"


@pytest.mark.asyncio
async def test_simple_route_parses_arbitrary_single_group_conditions() -> None:
    from app.models.candidate import CandidateContract
    from app.pipeline.analysis import AnalysisService, RecallPlan
    from app.graph.nodes import ScreeningGraphNodes
    from app.graph.workflow import build_screening_graph

    class MetricRetrieverMock:
        async def search(self, text: str, top_k: int):
            res = []
            if "规模" in text:
                res.append(CandidateContract("metric", "f_info_fundscale", "基金规模", aliases=("规模",), value_type="BILLION_CNY"))
            if "成立" in text:
                res.append(CandidateContract("metric", "f_info_existingyear", "成立年限", aliases=("成立年限", "成立"), value_type="YEAR"))
            return res

    class EmptyRetriever:
        async def search(self, text: str, top_k: int):
            return []

    analysis = AnalysisService(
        lambda q: RecallPlan(("metric",)),
        MetricRetrieverMock(),
        EmptyRetriever(),
        EmptyRetriever(),
        adjudicator=None,
    )
    nodes = ScreeningGraphNodes(analysis)
    graph = build_screening_graph(nodes)

    result = await graph.ainvoke(empty_state("规模大于10亿且成立超过5年"))
    assert result["route"] == "simple"
    assert result["final_dsl"] is not None
    metrics = result["final_dsl"]["children"][0]["metric"]["children"]
    metric_keys = [m["metric"] for m in metrics]
    assert "f_info_fundscale" in metric_keys
    assert "f_info_existingyear" in metric_keys

