import pytest
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


def test_command_builder_reconstructs_nested_metric_tree():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(condition_id="m1", mention="规模大于10亿"),
                        MetricTree(
                            operator="OR",
                            children=[
                                MetricLeaf(condition_id="m2", mention="成立大于5年"),
                                MetricLeaf(condition_id="m3", mention="成立大于3年"),
                            ],
                        ),
                    ],
                ),
            )
        ]
    )
    adjudication = AdjudicationResult(
        condition_bindings=[
            ConditionBinding(condition_id="m1", candidate_key="f_info_fundscale", operator=">", value=10, unit="亿"),
            ConditionBinding(condition_id="m2", candidate_key="f_info_existingyear", operator=">", value=5, unit="年"),
            ConditionBinding(condition_id="m3", candidate_key="f_info_existingyear", operator=">", value=3, unit="年"),
        ]
    )
    contracts = {
        "f_info_fundscale": CandidateContract("metric", "f_info_fundscale", "基金规模"),
        "f_info_existingyear": CandidateContract("metric", "f_info_existingyear", "成立年限"),
        "1000019220000000": CandidateContract("scope", "1000019220000000", "全市场"),
    }

    builder = CommandBuilder()
    dsl = builder.build_from_plan(plan, adjudication, contracts)

    assert dsl.version == "fund-screening/v1"
    group = dsl.children[0]
    # Sector fallback
    assert group.sector.children[0].sector_id == "1000019220000000"
    # Nested metric structure: m1 AND (m2 OR m3)
    metric_expr = group.metric
    assert metric_expr.operator == "AND"
    assert metric_expr.children[0].metric == "f_info_fundscale"
    sub_or = metric_expr.children[1]
    assert sub_or.operator == "OR"
    assert len(sub_or.children) == 2
    assert sub_or.children[0].value == 5
    assert sub_or.children[1].value == 3


def test_command_builder_binds_benchmark_index_as_parameter():
    # 测试指数实体被绑定为基准参数 (role: BENCHMARK -> parameter: indexCode)
    index_candidate = CandidateContract(
        domain="entity",
        key="000300.SH",
        name="沪深300",
        bindings=(
            {"role": "BENCHMARK", "targetParameter": "indexCode"},
        ),
    )
    metric_candidate = CandidateContract(
        domain="metric",
        key="f_beta",
        name="贝塔系数",
        parameters=(),
    )
    contracts = {
        "000300.SH": index_candidate,
        "f_beta": metric_candidate,
        "1000019220000000": CandidateContract("scope", "1000019220000000", "全市场"),
    }
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(
                    children=[
                        MetricLeaf(condition_id="m1", mention="沪深300贝塔大于1"),
                    ]
                ),
            )
        ]
    )
    adjudication = AdjudicationResult(
        condition_bindings=[
            ConditionBinding(
                condition_id="m1",
                candidate_key="f_beta",
                operator=">",
                value=1.0,
                params={"indexCode": "000300.SH"},
            )
        ]
    )
    builder = CommandBuilder()
    dsl = builder.build_from_plan(plan, adjudication, contracts)
    leaf = dsl.children[0].metric.children[0]
    assert leaf.metric == "f_beta"
    assert leaf.params == [{"key": "indexCode", "value": "000300.SH"}]


def test_logic_node_never_serializes_leaf_fields():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(condition_id="m1", mention="规模大于10亿"),
                    ],
                ),
            )
        ]
    )
    adjudication = AdjudicationResult(
        condition_bindings=[
            ConditionBinding(condition_id="m1", candidate_key="f_scale", operator=">", value=10, unit="亿"),
        ]
    )
    contracts = {
        "f_scale": CandidateContract("metric", "f_scale", "基金规模"),
        "1000019220000000": CandidateContract("scope", "1000019220000000", "全市场"),
    }
    builder = CommandBuilder()
    dsl = builder.build_from_plan(plan, adjudication, contracts)
    dumped_logic = dsl.children[0].metric.model_dump(exclude_none=True)
    assert "params" not in dumped_logic
    assert "metric" not in dumped_logic
    assert "value" not in dumped_logic
    assert "unit" not in dumped_logic


def test_metric_leaf_requires_value():
    from pydantic import ValidationError
    from app.models.dsl import MetricCondition
    # 当前 MetricCondition 允许 value 为 None，新契约应拒绝缺失 value
    with pytest.raises((ValidationError, ValueError)):
        MetricCondition(metric="f_scale", operator=">")


def test_sector_leaf_only_serializes_sector_id():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(children=[MetricLeaf(condition_id="m1", mention="x")]),
            )
        ]
    )
    adjudication = AdjudicationResult(
        condition_bindings=[
            ConditionBinding(condition_id="m1", candidate_key="f_scale", operator=">", value=10),
        ]
    )
    contracts = {
        "f_scale": CandidateContract("metric", "f_scale", "规模"),
        "1000019220000000": CandidateContract("scope", "1000019220000000", "全市场"),
    }
    builder = CommandBuilder()
    dsl = builder.build_from_plan(plan, adjudication, contracts)
    leaf_dump = dsl.children[0].sector.children[0].model_dump(by_alias=True)
    assert leaf_dump == {"sectorId": "1000019220000000"}


def test_group_and_root_have_stable_default_sort_and_limit():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(children=[MetricLeaf(condition_id="m1", mention="x")]),
            )
        ]
    )
    adjudication = AdjudicationResult(
        condition_bindings=[
            ConditionBinding(condition_id="m1", candidate_key="f_scale", operator=">", value=10),
        ]
    )
    contracts = {
        "f_scale": CandidateContract("metric", "f_scale", "规模"),
        "1000019220000000": CandidateContract("scope", "1000019220000000", "全市场"),
    }
    builder = CommandBuilder()
    dsl = builder.build_from_plan(plan, adjudication, contracts)
    assert dsl.children[0].sort and len(dsl.children[0].sort) > 0
    assert dsl.children[0].limit == 20
    assert dsl.sort and len(dsl.sort) > 0
    assert dsl.limit == 20

