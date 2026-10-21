import pytest
from app.models.semantic_plan import (
    SemanticPlan,
    PlanGroup,
    ScopeTree,
    ScopeLeaf,
    MetricTree,
    MetricLeaf,
    SortMention,
    ConditionBinding,
    SortBinding,
    AdjudicationResult,
)


def test_semantic_plan_model_serialization_and_validation():
    raw_plan = {
        "intent": "CREATE",
        "groupOperator": "AND",
        "groups": [
            {
                "groupId": "g1",
                "scopeTree": {
                    "operator": "OR",
                    "children": [
                        {"conditionId": "s1", "mention": "主动权益基金", "domain": "scope"}
                    ],
                },
                "metricTree": {
                    "operator": "AND",
                    "children": [
                        {
                            "conditionId": "m1",
                            "mention": "成立超过5年",
                            "domain": "metric",
                            "operatorHint": ">",
                            "valueHint": 5,
                            "unitHint": "年",
                        }
                    ],
                },
            }
        ],
        "sortMentions": [{"mention": "近一年收益率", "direction": "DESC"}],
        "limit": 20,
    }
    plan = SemanticPlan.model_validate(raw_plan)
    assert plan.intent == "CREATE"
    assert plan.group_operator == "AND"
    assert len(plan.groups) == 1
    group = plan.groups[0]
    assert group.group_id == "g1"
    assert len(group.scope_tree.children) == 1
    assert group.scope_tree.children[0].mention == "主动权益基金"
    assert group.metric_tree.children[0].operator_hint == ">"
    assert group.metric_tree.children[0].value_hint == 5


def test_semantic_plan_accepts_edit_as_the_only_non_create_intent():
    plan = SemanticPlan.model_validate({"intent": "EDIT"})

    assert plan.intent == "EDIT"

    with pytest.raises(ValueError):
        SemanticPlan.model_validate({"intent": "REBUILD"})

    with pytest.raises(ValueError):
        SemanticPlan.model_validate({"intent": "CLARIFY"})


def test_adjudication_result_model_validation():
    raw_adj = {
        "conditionBindings": [
            {
                "conditionId": "m1",
                "candidateKey": "f_info_existingyear",
                "bindingRole": None,
                "operator": ">",
                "value": 5,
                "unit": "年",
                "params": {"endDate": "LATEST_TRADE_DATE"},
            }
        ],
        "sortBindings": [
            {"mention": "近一年收益率", "candidateKey": "f_nav_return_1y", "direction": "DESC"}
        ],
        "clarifications": [],
    }
    result = AdjudicationResult.model_validate(raw_adj)
    assert len(result.condition_bindings) == 1
    binding = result.condition_bindings[0]
    assert binding.condition_id == "m1"
    assert binding.candidate_key == "f_info_existingyear"
    assert binding.operator == ">"
    assert binding.value == 5
    assert binding.params == {"endDate": "LATEST_TRADE_DATE"}
