import pytest
from langchain_core.runnables import RunnableLambda
from app.models.semantic_plan import (
    SemanticPlan,
    PlanGroup,
    MetricTree,
    MetricLeaf,
    AdjudicationResult,
    ConditionBinding,
)
from app.retrieval.llm import OpenAiSemanticLlm


class MockStructuredModel:
    def __init__(self, outputs):
        self.outputs = outputs
        self.calls = []

    def with_structured_output(self, schema_cls, *args, **kwargs):
        model = self
        async def _call(inp, **kwargs):
            model.calls.append((schema_cls, inp))
            return model.outputs[schema_cls]
        return RunnableLambda(_call)


@pytest.mark.asyncio
async def test_candidate_adjudication_outputs_leaf_bindings_only():
    mock_adj = AdjudicationResult(
        condition_bindings=[
            ConditionBinding(
                condition_id="m1",
                candidate_key="f_info_fundscale",
                binding_role=None,
                operator=">",
                value=10,
                unit="亿",
                params={},
            )
        ],
        sort_bindings=[],
        clarifications=[],
    )
    mock_model = MockStructuredModel({AdjudicationResult: mock_adj})
    llm = OpenAiSemanticLlm(chat_model=mock_model)

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
    assert result.condition_bindings[0].value == 10
    assert mock_model.calls[0][0] is AdjudicationResult


@pytest.mark.asyncio
async def test_candidate_adjudication_serializes_result_contract_metadata():
    import json
    from app.models.candidate import CandidateContract

    mock_adj = AdjudicationResult(condition_bindings=[], sort_bindings=[])
    mock_model = MockStructuredModel({AdjudicationResult: mock_adj})
    llm = OpenAiSemanticLlm(chat_model=mock_model)

    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(children=[MetricLeaf(condition_id="m1", mention="规模")]),
            )
        ]
    )
    cand = CandidateContract(
        domain="metric",
        key="f_netasset_total",
        name="基金规模合计",
        value_type="DECIMAL",
        supported_operators=[">"],
        result_contract={"unit": "元", "acceptedInputUnits": [{"unit": "亿元", "multiplier": 100000000}]},
    )
    by_condition = {
        "m1": {
            "mention": "规模",
            "domain": "metric",
            "candidates": [cand],
        }
    }
    await llm.adjudicate_candidates(plan, by_condition, route="simple")

    prompt_val = mock_model.calls[0][1]
    if hasattr(prompt_val, "to_messages"):
        context_str = prompt_val.to_messages()[-1].content
    elif isinstance(prompt_val, dict):
        context_str = prompt_val["context"]
    else:
        context_str = str(prompt_val)

    context_data = json.loads(context_str)
    cand_serialized = context_data["byCondition"]["m1"]["candidates"][0]

    assert cand_serialized.get("value_type") == "DECIMAL" or cand_serialized.get("valueType") == "DECIMAL"
    assert cand_serialized.get("unit") == "元" or (cand_serialized.get("resultContract") or {}).get("unit") == "元"
    assert "acceptedInputUnits" in cand_serialized or "acceptedInputUnits" in (cand_serialized.get("resultContract") or {})
    assert "supported_operators" in cand_serialized or "supportedOperators" in cand_serialized
    assert "parameters" in cand_serialized


