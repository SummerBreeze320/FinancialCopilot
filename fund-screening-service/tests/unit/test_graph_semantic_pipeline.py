import pytest
from app.graph.workflow import build_screening_graph
from app.graph.nodes import ScreeningGraphNodes
from app.graph.state import empty_state
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


class FakeAnalysis:
    def __init__(self, candidates):
        self.candidates = candidates
        self.plan_called = False
        self.recall_called = False

    async def _make_plan(self, query):
        self.plan_called = True
        return SemanticPlan(
            groups=[
                PlanGroup(
                    group_id="g1",
                    metric_tree=MetricTree(
                        children=[MetricLeaf(condition_id="m1", mention="规模大于10亿", domain="metric")]
                    ),
                )
            ]
        )

    async def recall_by_condition(self, plan):
        self.recall_called = True
        by_condition = {
            "m1": {
                "mention": "规模大于10亿",
                "domain": "metric",
                "candidates": self.candidates,
            }
        }
        contract_index = {c.key: c for c in self.candidates}
        return by_condition, contract_index

    async def adjudicate_candidates(self, plan, by_condition, route="simple"):
        return AdjudicationResult(
            condition_bindings=[
                ConditionBinding(
                    condition_id="m1",
                    candidate_key="f_info_fundscale",
                    operator=">",
                    value=10,
                    unit="亿",
                )
            ]
        )


@pytest.mark.asyncio
async def test_graph_executes_strict_direct_probe_for_code():
    candidate = CandidateContract("entity", "001938.OF", "中欧时代先锋股票A", aliases=("001938",), preferred_metric="f_info_fundcode")
    analysis = FakeAnalysis([candidate])
    nodes = ScreeningGraphNodes(analysis, CommandBuilder(), candidates_catalog=[candidate])
    graph = build_screening_graph(nodes)

    state = empty_state("001938")
    result = await graph.ainvoke(state)

    assert result.get("route") == "direct"
    assert result.get("final_dsl") is not None
    assert result["final_dsl"]["children"][0]["metric"]["children"][0]["value"] == "001938.OF"
    # Strict direct probe skips LLM plan and recall
    assert analysis.plan_called is False


@pytest.mark.asyncio
async def test_graph_executes_semantic_routing_full_pipeline():
    candidate = CandidateContract("metric", "f_info_fundscale", "基金规模", supported_operators=(">", ">="))
    analysis = FakeAnalysis([candidate])
    nodes = ScreeningGraphNodes(analysis, CommandBuilder(), candidates_catalog=[candidate])
    graph = build_screening_graph(nodes)

    state = empty_state("规模大于10亿")
    result = await graph.ainvoke(state)

    assert result.get("route") == "simple"
    assert result.get("final_dsl") is not None
    leaf = result["final_dsl"]["children"][0]["metric"]["children"][0]
    assert leaf["metric"] == "f_info_fundscale"
    assert leaf["value"] == 10
    assert analysis.plan_called is True
    assert analysis.recall_called is True


@pytest.mark.asyncio
async def test_graph_compiler_trace_fields():
    from app.graph.compiler import LangGraphCommandCompiler

    candidates = [
        CandidateContract("metric", "f_info_fundscale", "基金规模", supported_operators=(">", ">="), score=0.90),
        CandidateContract("metric", "m2", "指标2", score=0.60),
        CandidateContract("metric", "m3", "指标3", score=0.40),
        CandidateContract("metric", "f_info_existingyear", "成立年限", supported_operators=(">", ">="), score=0.92),
        CandidateContract("metric", "m5", "指标5", score=0.50),
        CandidateContract("metric", "m6", "指标6", score=0.30),
    ]

    class TwoConditionAnalysis(FakeAnalysis):
        async def _make_plan(self, query):
            self.plan_called = True
            return SemanticPlan(
                groups=[
                    PlanGroup(
                        group_id="g1",
                        metric_tree=MetricTree(
                            operator="AND",
                            children=[
                                MetricLeaf(condition_id="m1", mention="规模大于10亿", domain="metric"),
                                MetricLeaf(condition_id="m2", mention="成立超过5年", domain="metric"),
                            ],
                        ),
                    )
                ]
            )

        async def recall_by_condition(self, plan):
            self.recall_called = True
            by_condition = {
                "m1": {"mention": "规模大于10亿", "candidates": self.candidates[:3]},
                "m2": {"mention": "成立超过5年", "candidates": self.candidates[3:]},
            }
            contract_index = {c.key: c for c in self.candidates}
            return by_condition, contract_index

        async def adjudicate_candidates(self, plan, by_condition, route="simple"):
            return AdjudicationResult(
                condition_bindings=[
                    ConditionBinding(condition_id="m1", candidate_key="f_info_fundscale", operator=">", value=10, unit="亿"),
                    ConditionBinding(condition_id="m2", candidate_key="f_info_existingyear", operator=">", value=5, unit="年"),
                ]
            )

    analysis = TwoConditionAnalysis(candidates)
    nodes = ScreeningGraphNodes(analysis, CommandBuilder(), candidates_catalog=candidates)
    graph = build_screening_graph(nodes)
    compiler = LangGraphCommandCompiler(graph)

    result = await compiler.compile("规模大于10亿且成立超过5年")
    assert result.trace == {
        "route": "simple",
        "route_reason": "SINGLE_GROUP_SHALLOW_AND",
        "group_count": 1,
        "condition_count": 2,
        "recalled_condition_count": 2,
        "candidate_count": 6,
        "binding_count": 2,
        "llm_calls": 2,
        "repairs": [],
    }
