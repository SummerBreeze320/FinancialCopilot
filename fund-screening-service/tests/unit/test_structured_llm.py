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
