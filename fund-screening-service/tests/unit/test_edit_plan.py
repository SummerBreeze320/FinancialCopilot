import pytest
from app.models.semantic_plan import (
    SemanticPlan,
    PlanGroup,
    MetricTree,
    MetricLeaf,
    ScopeTree,
    ScopeLeaf,
    SortMention,
)
from app.models.edit_plan import EditPlan, EditOperation
from app.pipeline.editor import apply_edit_plan, summarize_plan_for_edit


@pytest.fixture
def base_plan():
    return SemanticPlan(
        intent="CREATE",
        groups=[
            PlanGroup(
                groupId="g1",
                scopeTree=ScopeTree(operator="OR", children=[ScopeLeaf(conditionId="s1", mention="偏股混合型")]),
                metricTree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(conditionId="m1", mention="规模大于10亿", operatorHint=">", valueHint=10.0, unitHint="亿"),
                        MetricLeaf(conditionId="m2", mention="成立年限大于3年", operatorHint=">", valueHint=3.0, unitHint="年"),
                    ],
                ),
            )
        ],
        sortMentions=[SortMention(mention="规模", direction="DESC")],
        limit=20,
    )


def test_summarize_plan_for_edit(base_plan):
    summary = summarize_plan_for_edit(base_plan)
    assert len(summary["conditions"]) == 3
    c_ids = [c["conditionId"] for c in summary["conditions"]]
    assert "s1" in c_ids and "m1" in c_ids and "m2" in c_ids


def test_apply_edit_plan_replace_condition(base_plan):
    edit = EditPlan(
        operations=[
            EditOperation(
                action="REPLACE",
                targetConditionId="m1",
                condition=MetricLeaf(conditionId="m1_rep", mention="规模大于20亿", operatorHint=">", valueHint=20.0, unitHint="亿"),
            )
        ]
    )
    new_plan, recall_ids = apply_edit_plan(base_plan, edit)
    assert recall_ids == ["m1_rep"]
    assert len(new_plan.groups[0].metric_tree.children) == 2
    # m2 is untouched
    assert new_plan.groups[0].metric_tree.children[1].condition_id == "m2"
    # m1 replaced
    assert new_plan.groups[0].metric_tree.children[0].condition_id == "m1_rep"
    assert new_plan.groups[0].metric_tree.children[0].value_hint == 20.0


def test_apply_edit_plan_add_condition(base_plan):
    edit = EditPlan(
        operations=[
            EditOperation(
                action="ADD",
                groupId="g1",
                condition=MetricLeaf(conditionId="m3", mention="近1年收益大于10%", operatorHint=">", valueHint=10.0, unitHint="%"),
            )
        ]
    )
    new_plan, recall_ids = apply_edit_plan(base_plan, edit)
    assert recall_ids == ["m3"]
    assert len(new_plan.groups[0].metric_tree.children) == 3
    assert new_plan.groups[0].metric_tree.children[2].condition_id == "m3"


def test_apply_edit_plan_remove_condition(base_plan):
    edit = EditPlan(
        operations=[
            EditOperation(
                action="REMOVE",
                targetConditionId="m1",
            )
        ]
    )
    new_plan, recall_ids = apply_edit_plan(base_plan, edit)
    assert recall_ids == []
    assert len(new_plan.groups[0].metric_tree.children) == 1
    assert new_plan.groups[0].metric_tree.children[0].condition_id == "m2"


def test_apply_edit_plan_reset(base_plan):
    edit = EditPlan(
        operations=[
            EditOperation(action="RESET")
        ]
    )
    new_plan, recall_ids = apply_edit_plan(base_plan, edit)
    assert recall_ids == []
    assert len(new_plan.groups) == 0
