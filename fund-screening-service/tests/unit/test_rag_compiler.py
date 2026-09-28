import pytest

from app.models.candidate import CandidateContract
from app.pipeline.analysis import AnalysisService, ConditionPlan, RecallPlan
from app.pipeline.compiler import RagCommandCompiler


class FakeRetriever:
    def __init__(self, candidates):
        self.candidates = candidates

    async def search(self, text, top_k):
        return self.candidates


@pytest.mark.asyncio
async def test_rag_compiler_runs_plan_recall_adjudication_build_and_repair() -> None:
    metric_retriever = FakeRetriever([
        CandidateContract("metric", "f_risk_maxdownside", "最大回撤", supported_usage=("filter", "sort")),
        CandidateContract("metric", "f_info_fundscale", "基金规模", supported_usage=("filter", "sort")),
    ])
    scope_retriever = FakeRetriever([
        CandidateContract("scope", "1000002533000000", "股票型开放式基金"),
    ])

    def plan_generator(query):
        return RecallPlan(
            ("metric", "scope"),
            (ConditionPlan("m1", "最大回撤和规模", "metric"), ConditionPlan("s1", "主动权益基金", "scope")),
        )

    async def adjudicator(query, plan, pool):
        return {"groups": [{
            "sectorIds": ["1000002533000000"],
            "metrics": [
                {"metric": "f_risk_maxdownside", "operator": "<", "value": 15, "unit": "PERCENT"},
                {"metric": "f_info_fundscale", "operator": ">", "value": 10, "unit": "BILLION_CNY"},
            ],
        }], "limit": 20}

    analysis = AnalysisService(plan_generator, metric_retriever, scope_retriever, FakeRetriever([]), adjudicator)
    result = await RagCommandCompiler(analysis).compile("筛选回撤低于15%、规模大于10亿的主动权益基金")

    leaf = result.dsl.children[0].metric.children[0]
    assert (leaf.metric, leaf.operator, leaf.value) == ("f_risk_maxdownside", ">=", -15)
    assert result.trace["candidate_count"] == 3
    assert result.trace["route"] == "full"
