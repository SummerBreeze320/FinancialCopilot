import pytest
from app.models.candidate import CandidateContract
from app.models.dsl import FundScreeningDsl, BusinessGroup, SectorExpression, SectorLeaf
from app.models.dsl_nodes import MetricLogicNode, MetricPredicate
from app.pipeline.validator import validate_and_repair


def test_validator_converts_drawdown_negative_and_inverts_operator():
    contract = CandidateContract(
        domain="metric",
        key="f_risk_maxdownside",
        name="最大回撤",
        result_contract={"storageConvention": "NEGATIVE_PERCENT", "supportedOperators": [">=", "<="]},
    )
    dsl = FundScreeningDsl(
        version="fund-screening/v1",
        operator="AND",
        children=[
            BusinessGroup(
                sector=SectorExpression(operator="OR", children=[SectorLeaf(sectorId="1000019220000000")]),
                metric=MetricLogicNode(
                    operator="AND",
                    children=[
                        MetricPredicate(operator="<=", metric="f_risk_maxdownside", value=15.0, unit="%")
                    ],
                ),
            )
        ],
        limit=20,
    )
    res = validate_and_repair(dsl, {"f_risk_maxdownside": contract})
    leaf = res.dsl.children[0].metric.children[0]
    assert leaf.value == -15.0
    assert leaf.operator == ">="
    assert "NEGATIVE_DRAWDOWN_STORAGE" in res.repairs


def test_validator_nested_metric_tree_validation_and_repair():
    contract = CandidateContract(
        domain="metric",
        key="f_risk_maxdownside",
        name="最大回撤",
        result_contract={"storageConvention": "NEGATIVE_PERCENT", "supportedOperators": [">=", "<="]},
    )
    dsl = FundScreeningDsl(
        version="fund-screening/v1",
        operator="AND",
        children=[
            BusinessGroup(
                sector=SectorExpression(operator="OR", children=[SectorLeaf(sectorId="1000019220000000")]),
                metric=MetricLogicNode(
                    operator="AND",
                    children=[
                        MetricLogicNode(
                            operator="OR",
                            children=[
                                MetricPredicate(operator="<=", metric="f_risk_maxdownside", value=10.0, unit="%"),
                                MetricPredicate(operator="<=", metric="f_risk_maxdownside", value=15.0, unit="%"),
                            ],
                        )
                    ],
                ),
            )
        ],
        limit=20,
    )
    res = validate_and_repair(dsl, {"f_risk_maxdownside": contract})
    sub_or = res.dsl.children[0].metric.children[0]
    assert sub_or.children[0].value == -10.0
    assert sub_or.children[0].operator == ">="
    assert sub_or.children[1].value == -15.0
    assert sub_or.children[1].operator == ">="
