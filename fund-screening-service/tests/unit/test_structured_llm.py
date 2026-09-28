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


def test_decision_output_rejects_comparison_operator_as_group_operator() -> None:
    with pytest.raises(ValidationError):
        DecisionGroupOutput.model_validate({"operator": "<", "metrics": []})


def test_decision_output_requires_sort_to_be_array() -> None:
    with pytest.raises(ValidationError):
        DecisionOutput.model_validate({"groups": [], "sort": {"metric": "x"}, "limit": 20})


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


class MockStructuredModel:
    def __init__(self, outputs):
        self.outputs = outputs
        self.calls = []
        self.methods = []

    def with_structured_output(self, schema_cls, *args, **kwargs):
        from langchain_core.runnables import RunnableLambda
        model = self
        model.methods.append(kwargs.get("method"))
        async def _call(inp, **kwargs):
            model.calls.append((schema_cls, inp))
            return model.outputs[schema_cls]
        return RunnableLambda(_call)


@pytest.mark.asyncio
async def test_openai_semantic_llm_uses_structured_runnable() -> None:
    from app.pipeline.analysis import CandidatePool
    from app.models.candidate import CandidateContract
    from app.retrieval.llm import OpenAiSemanticLlm

    mock_plan = RecallPlanOutput(conditions=[ConditionOutput(condition_id="c1", mention="回撤", domain="metric")])
    mock_dec = DecisionOutput(groups=[DecisionGroupOutput(metrics=[MetricPredicateOutput(metric="f_risk_maxdownside", operator="<", value=10, unit="PERCENT")])])
    mock_model = MockStructuredModel({RecallPlanOutput: mock_plan, DecisionOutput: mock_dec})

    llm = OpenAiSemanticLlm(chat_model=mock_model)
    plan = await llm.generate_plan("回撤小于10%")
    assert plan.conditions[0].mention == "回撤"
    assert mock_model.calls[0][0] is RecallPlanOutput
    assert mock_model.methods == ["json_schema", "json_schema"]

    decision = await llm.adjudicate("回撤小于10%", plan, CandidatePool([CandidateContract("metric", "f_risk_maxdownside", "最大回撤")]))
    assert decision["groups"][0]["metrics"][0]["metric"] == "f_risk_maxdownside"
    assert mock_model.calls[1][0] is DecisionOutput


@pytest.mark.asyncio
async def test_full_path_strictly_two_llm_calls() -> None:
    from app.pipeline.analysis import AnalysisService, CandidatePool
    from app.models.candidate import CandidateContract
    from app.graph.nodes import ScreeningGraphNodes
    from app.graph.workflow import build_screening_graph
    from app.graph.state import empty_state
    from app.pipeline.builder import CommandBuilder
    from app.retrieval.llm import OpenAiSemanticLlm

    mock_plan = RecallPlanOutput(conditions=[ConditionOutput(condition_id="c1", mention="回撤", domain="metric")])
    mock_dec = DecisionOutput(groups=[DecisionGroupOutput(metrics=[MetricPredicateOutput(metric="f_risk_maxdownside", operator="<", value=10, unit="PERCENT")])])
    mock_model = MockStructuredModel({RecallPlanOutput: mock_plan, DecisionOutput: mock_dec})

    llm = OpenAiSemanticLlm(chat_model=mock_model)

    class DummyMetricRetriever:
        async def search(self, text: str, top_k: int):
            return [CandidateContract("metric", "f_risk_maxdownside", "最大回撤")]

    class DummyScopeRetriever:
        async def search(self, text: str, top_k: int):
            return [CandidateContract("scope", "1000019220000000", "全市场")]

    analysis = AnalysisService(
        llm.generate_plan,
        DummyMetricRetriever(),
        DummyScopeRetriever(),
        DummyMetricRetriever(),
        llm.adjudicate,
    )
    nodes = ScreeningGraphNodes(analysis, CommandBuilder())
    graph = build_screening_graph(nodes)

    # Complex query that forces full route
    state = empty_state("规模大于10亿或者最大回撤小于10%")
    final_state = await graph.ainvoke(state)

    assert final_state["route"] == "full"
    assert final_state["final_dsl"] is not None
    # Strictly 2 LLM calls: call 1 for RecallPlanOutput, call 2 for DecisionOutput
    assert len(mock_model.calls) == 2
    assert mock_model.calls[0][0] is RecallPlanOutput
    assert mock_model.calls[1][0] is DecisionOutput


