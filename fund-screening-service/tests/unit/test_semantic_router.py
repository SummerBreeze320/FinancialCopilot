import pytest
from app.graph.router import classify_plan_route
from app.models.semantic_plan import SemanticPlan, PlanGroup, ScopeTree, ScopeLeaf, MetricTree, MetricLeaf


def test_classify_simple_route_for_single_group_shallow_and():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                scope_tree=ScopeTree(children=[ScopeLeaf(condition_id="s1", mention="混合基金")]),
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(condition_id="m1", mention="规模大于10亿"),
                        MetricLeaf(condition_id="m2", mention="成立超过3年"),
                    ],
                ),
            )
        ]
    )
    by_condition = {
        "s1": {"candidates": [{"score": 0.95}]},
        "m1": {"candidates": [{"score": 0.92}]},
        "m2": {"candidates": [{"score": 0.90}]},
    }
    route, reason = classify_plan_route(plan, by_condition)
    assert route == "simple"
    assert "SINGLE_GROUP_SHALLOW" in reason


def test_classify_full_route_for_multi_groups():
    plan = SemanticPlan(
        group_operator="OR",
        groups=[
            PlanGroup(group_id="g1"),
            PlanGroup(group_id="g2"),
        ]
    )
    route, reason = classify_plan_route(plan, {})
    assert route == "full"
    assert "MULTI_GROUP" in reason


def test_classify_full_route_for_tree_with_not():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(
                    operator="NOT",
                    children=[MetricLeaf(condition_id="m1", mention="回撤大于20%")],
                ),
            )
        ]
    )
    route, reason = classify_plan_route(plan, {})
    assert route == "full"
    assert "NOT_OPERATOR" in reason


def test_classify_full_route_when_too_many_conditions():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(condition_id="m1", mention="c1"),
                        MetricLeaf(condition_id="m2", mention="c2"),
                        MetricLeaf(condition_id="m3", mention="c3"),
                        MetricLeaf(condition_id="m4", mention="c4"),
                    ],
                ),
            )
        ]
    )
    route, reason = classify_plan_route(plan, {})
    assert route == "full"
    assert "CONDITION_COUNT" in reason
