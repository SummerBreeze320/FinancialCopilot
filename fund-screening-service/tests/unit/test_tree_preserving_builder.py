from __future__ import annotations

import pytest
from app.models.candidate import CandidateContract
from app.models.semantic_plan import (
    AdjudicationResult,
    MetricLeaf,
    MetricTree,
    PlanGroup,
    ResolvedCondition,
    ScopeLeaf,
    ScopeTree,
    SemanticPlan,
    SortBinding,
)
from app.pipeline.builder import CommandBuilder


def test_builder_strictly_retains_nested_tree_shape():
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
                                MetricLeaf(conditionId="c2", mention="成立超过5年"),
                                MetricLeaf(conditionId="c3", mention="成立超过3年"),
                            ],
                        ),
                    ],
                )
            )
        ]
    )
    adjudication = AdjudicationResult(
        condition_bindings=[
            ResolvedCondition(conditionId="c1", selectedCandidateId="f_scale", operator=">", value=10.0),
            ResolvedCondition(conditionId="c2", selectedCandidateId="f_year", operator=">", value=5.0),
            ResolvedCondition(conditionId="c3", selectedCandidateId="f_year", operator=">", value=3.0),
        ]
    )
    contracts = {
        "f_scale": CandidateContract("metric", "f_scale", "规模"),
        "f_year": CandidateContract("metric", "f_year", "年限"),
        "1000019220000000": CandidateContract("scope", "1000019220000000", "全市场"),
    }
    builder = CommandBuilder()
    dsl = builder.build_from_plan(plan, adjudication, contracts)

    root_metric = dsl.children[0].metric
    assert root_metric.operator == "AND"
    assert len(root_metric.children) == 2
    # Subtree operator must be OR
    assert root_metric.children[1].operator == "OR"
    assert len(root_metric.children[1].children) == 2


def test_builder_produces_identical_stable_dsl():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(conditionId="c1", mention="规模大于10亿"),
                    ],
                )
            )
        ],
        sort_mentions=[],
        limit=50,
    )
    adjudication = AdjudicationResult(
        condition_bindings=[
            ResolvedCondition(conditionId="c1", selectedCandidateId="f_scale", operator=">", value=10.0),
        ],
        sort_bindings=[
            SortBinding(mention="规模", candidateKey="f_scale", direction="DESC")
        ],
    )
    contracts = {
        "f_scale": CandidateContract("metric", "f_scale", "规模"),
        "1000019220000000": CandidateContract("scope", "1000019220000000", "全市场"),
    }
    builder = CommandBuilder()
    dsl1 = builder.build_from_plan(plan, adjudication, contracts)
    dsl2 = builder.build_from_plan(plan, adjudication, contracts)

    assert dsl1.model_dump() == dsl2.model_dump()
    assert dsl1.limit == 50
    assert dsl1.sort[0].metric == "f_scale"
    assert dsl1.sort[0].direction == "DESC"
