from __future__ import annotations

import pytest

from app.models.semantic_plan import (
    SemanticPlan,
    PlanGroup,
    MetricTree,
    MetricLeaf,
    ScopeTree,
    ScopeLeaf,
    AdjudicationResult,
    MetricConditionBinding,
)
from app.pipeline.semantic_coverage import (
    check_semantic_coverage,
    check_binding_coverage,
    extract_query_comparisons,
    CoverageResult,
)


def test_extract_query_comparisons():
    # 提取明确出现的数值比较片段
    query = "规模大于10亿且成立超过5年，最大回撤<15%"
    fragments = extract_query_comparisons(query)
    assert len(fragments) >= 3
    # Check that scale, existing year, and drawdown fragments were captured
    assert any("10" in f.text for f in fragments)
    assert any("5" in f.text for f in fragments)
    assert any("15" in f.text for f in fragments)


def test_coverage_fails_when_numeric_condition_dropped():
    query = "规模大于10亿且成立超过5年"
    # Plan only has 成立超过5年, drops 规模大于10亿
    plan = SemanticPlan(
        intent="CREATE",
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(condition_id="m1", mention="成立超过5年", operator_hint=">", value_hint=5),
                    ],
                ),
            )
        ],
    )
    res = check_semantic_coverage(query, plan)
    assert not res.is_valid
    assert len(res.missing_fragments) > 0
    assert any("10" in f for f in res.missing_fragments)


def test_coverage_fails_on_unwarranted_zero_placeholder():
    query = "规模大于10亿且成立超过5年"
    # Plan has 0 placeholder when 0 is not in query
    plan = SemanticPlan(
        intent="CREATE",
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(condition_id="m1", mention="规模大于10亿", operator_hint=">", value_hint=10),
                        MetricLeaf(condition_id="m2", mention="成立年限大于0", operator_hint=">", value_hint=0),
                    ],
                ),
            )
        ],
    )
    res = check_semantic_coverage(query, plan)
    assert not res.is_valid
    assert res.has_zero_placeholder


def test_coverage_passes_when_all_conditions_represented():
    query = "规模大于10亿且成立超过5年"
    plan = SemanticPlan(
        intent="CREATE",
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(condition_id="m1", mention="规模大于10亿", operator_hint=">", value_hint=10),
                        MetricLeaf(condition_id="m2", mention="成立超过5年", operator_hint=">", value_hint=5),
                    ],
                ),
            )
        ],
    )
    res = check_semantic_coverage(query, plan)
    assert res.is_valid
    assert not res.missing_fragments
    assert not res.has_zero_placeholder


def test_binding_coverage_detects_unbound_conditions():
    plan = SemanticPlan(
        intent="CREATE",
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(condition_id="m1", mention="规模大于10亿", operator_hint=">", value_hint=10),
                        MetricLeaf(condition_id="m2", mention="成立超过5年", operator_hint=">", value_hint=5),
                    ],
                ),
            )
        ],
    )
    # Adjudication only binds m1
    adj = AdjudicationResult(
        condition_bindings=[
            MetricConditionBinding(condition_id="m1", candidate_key="f_info_fundscale", operator=">", value=10),
        ],
        sort_bindings=[],
        clarifications=[],
    )
    res = check_binding_coverage(plan, adj)
    assert not res.is_valid
    assert res.unbound_condition_ids == ["m2"]


def test_binding_coverage_passes_when_all_bound_or_clarified():
    plan = SemanticPlan(
        intent="CREATE",
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(condition_id="m1", mention="规模大于10亿", operator_hint=">", value_hint=10),
                        MetricLeaf(condition_id="m2", mention="成立超过5年", operator_hint=">", value_hint=5),
                    ],
                ),
            )
        ],
    )
    adj = AdjudicationResult(
        condition_bindings=[
            MetricConditionBinding(condition_id="m1", candidate_key="f_info_fundscale", operator=">", value=10),
            MetricConditionBinding(condition_id="m2", candidate_key="f_info_existingyear", operator=">", value=5),
        ],
        sort_bindings=[],
        clarifications=[],
    )
    res = check_binding_coverage(plan, adj)
    assert res.is_valid
    assert not res.unbound_condition_ids
