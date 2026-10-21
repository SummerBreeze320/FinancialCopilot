import pytest
from app.models.candidate import CandidateContract
from app.models.semantic_plan import SemanticPlan, PlanGroup, ScopeTree, ScopeLeaf, MetricTree, MetricLeaf
from app.pipeline.analysis import RecallByConditionService


class FakeRetriever:
    def __init__(self, items):
        self.items = items
        self.calls = []

    async def search(self, query, top_k=10):
        self.calls.append((query, top_k))
        return self.items


@pytest.mark.asyncio
async def test_condition_level_recall_dispatches_by_domain():
    metric_candidate = CandidateContract("metric", "f_info_fundscale", "基金规模")
    scope_candidate = CandidateContract("scope", "1000002533000000", "普通股票型基金")

    metric_retriever = FakeRetriever([metric_candidate])
    scope_retriever = FakeRetriever([scope_candidate])
    entity_retriever = FakeRetriever([])

    service = RecallByConditionService(
        metric_retriever=metric_retriever,
        scope_retriever=scope_retriever,
        entity_retriever=entity_retriever,
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
    assert metric_retriever.calls == [("规模大于10亿", 10)]
    assert scope_retriever.calls == [("股票基金", 10)]


def test_candidate_contract_supports_bindings_for_multi_role():
    c = CandidateContract(
        domain="entity",
        key="000300.SH",
        name="沪深300",
        bindings=(
            {"role": "TRACKED_INDEX", "targetMetric": "f_info_trackindex", "operator": "="},
            {"role": "BENCHMARK", "targetParameter": "indexCode"},
        ),
    )
    assert len(c.bindings) == 2
    assert c.bindings[0]["role"] == "TRACKED_INDEX"
    assert c.bindings[1]["targetParameter"] == "indexCode"
