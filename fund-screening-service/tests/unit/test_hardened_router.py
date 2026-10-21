from __future__ import annotations

import pytest
from app.graph.router import classify_plan_route
from app.models.candidate import ConditionCandidateView
from app.models.semantic_plan import MetricLeaf, MetricTree, PlanGroup, SemanticPlan


def test_classify_plan_route_full_on_nested_or():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(conditionId="c1", mention="规模大于10亿"),
                        MetricTree(
                            operator="OR",
                            children=[
                                MetricLeaf(conditionId="c2", mention="成立大于5年"),
                                MetricLeaf(conditionId="c3", mention="成立大于3年"),
                            ],
                        ),
                    ],
                )
            )
        ]
    )
    route, reason = classify_plan_route(plan, {})
    assert route == "full"
    assert "OR" in reason or "NESTED" in reason


def test_classify_plan_route_full_on_candidate_ambiguity():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(conditionId="c1", mention="收益大于10%"),
                    ],
                )
            )
        ]
    )
    # Top 1 and Top 2 have close scores (gap < 0.15 with high scores)
    candidate_views = {
        "c1": [
            ConditionCandidateView(condition_id="c1", candidate_type="metric", canonical_id="f_return_1y", display_name="近1年收益率", retrieval_channel="pgvector", retrieval_score=0.92, rank=1),
            ConditionCandidateView(condition_id="c1", candidate_type="metric", canonical_id="f_return_3y", display_name="近3年收益率", retrieval_channel="pgvector", retrieval_score=0.89, rank=2),
        ]
    }
    route, reason = classify_plan_route(plan, candidate_views)
    assert route == "full"
    assert "AMBIGUITY" in reason or "CLOSE_SCORE" in reason


def test_classify_plan_route_direct_on_exact_matches():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(conditionId="c1", mention="规模大于10亿", operatorHint=">", valueHint=10.0),
                    ],
                )
            )
        ]
    )
    candidate_views = {
        "c1": [
            ConditionCandidateView(condition_id="c1", candidate_type="metric", canonical_id="f_scale", display_name="基金规模", retrieval_channel="direct", retrieval_score=1.0, rank=1)
        ]
    }
    route, reason = classify_plan_route(plan, candidate_views)
    assert route == "simple"
    assert "DETERMINISTIC" in reason or "EXACT" in reason


def test_classify_plan_route_simple_on_shallow_and():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(conditionId="c1", mention="规模大于10亿"),
                        MetricLeaf(conditionId="c2", mention="成立大于5年"),
                    ],
                )
            )
        ]
    )
    candidate_views = {
        "c1": [
            ConditionCandidateView(condition_id="c1", candidate_type="metric", canonical_id="f_scale", display_name="基金规模", retrieval_channel="pgvector", retrieval_score=0.95, rank=1),
            ConditionCandidateView(condition_id="c1", candidate_type="metric", canonical_id="f_other", display_name="其他", retrieval_channel="pgvector", retrieval_score=0.40, rank=2),
        ],
        "c2": [
            ConditionCandidateView(condition_id="c2", candidate_type="metric", canonical_id="f_year", display_name="成立年限", retrieval_channel="pgvector", retrieval_score=0.90, rank=1),
        ],
    }
    route, reason = classify_plan_route(plan, candidate_views)
    assert route == "simple"
