from __future__ import annotations

import pytest

from app.models.candidate import CandidateContract
from app.models.parameters import MetricContract, MetricParameterDefinition, ParameterOption, ResultContract
from app.pipeline.function_serializer import serialize_metric_call


def create_bond_credit_rating_contract() -> MetricContract:
    return MetricContract(
        mnemonic="f_prt_bondbycreditratingtonav",
        parameters=[
            MetricParameterDefinition(
                position=1,
                key="windCode",
                valueType="STRING",
                semanticType="FUND_CODE",
                required=True,
                description="基金 Wind 代码",
            ),
            MetricParameterDefinition(
                position=2,
                key="reportDate",
                valueType="DATE",
                semanticType="REPORT_DATE",
                format="yyyyMMdd",
                required=True,
                defaultValue="LATEST_REPORT_DATE",
                description="报告期",
            ),
            MetricParameterDefinition(
                position=3,
                key="ratingTerm",
                valueType="ENUM",
                required=True,
                options=[
                    ParameterOption(label="长期信用评级", value=1),
                    ParameterOption(label="短期信用评级", value=2),
                ],
                description="评级期限类型",
            ),
            MetricParameterDefinition(
                position=4,
                key="creditRating",
                valueType="ENUM",
                required=True,
                options=[
                    ParameterOption(label="AAA", value=1),
                    ParameterOption(label="AA+", value=2),
                    ParameterOption(label="AA", value=3),
                ],
                description="信用评级",
            ),
        ],
        result=ResultContract(
            valueType="DECIMAL",
            unit="%",
            supportedOperators=[">", ">=", "<", "<=", "=", "!="],
        ),
    )


def test_serialize_metric_call_respects_position_order():
    contract = create_bond_credit_rating_contract()
    # Scramble dictionary input order
    values = {
        "creditRating": 1,
        "windCode": "000001.OF",
        "ratingTerm": 1,
        "reportDate": "20260630",
    }
    result = serialize_metric_call("f_prt_bondbycreditratingtonav", contract, values)
    assert result == "f_prt_bondbycreditratingtonav(000001.OF,20260630,1,1)"


def test_serialize_with_candidate_contract():
    metric_contract = create_bond_credit_rating_contract()
    candidate = CandidateContract(
        domain="metric",
        key="f_prt_bondbycreditratingtonav",
        name="按信用等级分类的债券投资市值占净价比例",
        parameters=tuple(metric_contract.parameters),
        value_type="DECIMAL",
    )
    values = {
        "creditRating": 2,
        "windCode": "000001.OF",
        "ratingTerm": 1,
        "reportDate": "20260630",
    }
    result = serialize_metric_call("f_prt_bondbycreditratingtonav", candidate, values)
    assert result == "f_prt_bondbycreditratingtonav(000001.OF,20260630,1,2)"


def test_serialize_missing_required_parameter_raises():
    contract = create_bond_credit_rating_contract()
    values = {
        "windCode": "000001.OF",
        "reportDate": "20260630",
        "ratingTerm": 1,
        # missing creditRating
    }
    with pytest.raises(ValueError, match="creditRating"):
        serialize_metric_call("f_prt_bondbycreditratingtonav", contract, values)


def test_serialize_unknown_key_raises():
    contract = create_bond_credit_rating_contract()
    values = {
        "windCode": "000001.OF",
        "reportDate": "20260630",
        "ratingTerm": 1,
        "creditRating": 1,
        "unexpectedParam": "foo",
    }
    with pytest.raises(ValueError, match="unexpectedParam"):
        serialize_metric_call("f_prt_bondbycreditratingtonav", contract, values)


def test_serialize_invalid_enum_raises():
    contract = create_bond_credit_rating_contract()
    values = {
        "windCode": "000001.OF",
        "reportDate": "20260630",
        "ratingTerm": 999,  # invalid enum
        "creditRating": 1,
    }
    with pytest.raises(ValueError, match="ratingTerm"):
        serialize_metric_call("f_prt_bondbycreditratingtonav", contract, values)


def test_serialize_invalid_date_format_raises():
    contract = create_bond_credit_rating_contract()
    values = {
        "windCode": "000001.OF",
        "reportDate": "2026-06-30",  # invalid format, must be yyyyMMdd
        "ratingTerm": 1,
        "creditRating": 1,
    }
    with pytest.raises(ValueError, match="reportDate"):
        serialize_metric_call("f_prt_bondbycreditratingtonav", contract, values)


def test_serialize_date_range_inverted_raises():
    contract = MetricContract(
        mnemonic="f_nav_adjusted",
        parameters=[
            MetricParameterDefinition(
                position=1,
                key="windCode",
                valueType="STRING",
                semanticType="FUND_CODE",
                required=True,
            ),
            MetricParameterDefinition(
                position=2,
                key="startDate",
                valueType="DATE",
                semanticType="TRADE_DATE",
                format="yyyyMMdd",
                pairWith="endDate",
                required=True,
            ),
            MetricParameterDefinition(
                position=3,
                key="endDate",
                valueType="DATE",
                semanticType="TRADE_DATE",
                format="yyyyMMdd",
                pairWith="startDate",
                required=True,
            ),
        ],
    )
    values = {
        "windCode": "000001.OF",
        "startDate": "20261231",
        "endDate": "20260101",
    }
    with pytest.raises(ValueError, match="startDate must be <= endDate"):
        serialize_metric_call("f_nav_adjusted", contract, values)


def test_serialize_with_default_value():
    contract = create_bond_credit_rating_contract()
    # reportDate has defaultValue: "LATEST_REPORT_DATE"
    values = {
        "windCode": "000001.OF",
        "ratingTerm": 1,
        "creditRating": 1,
    }
    result = serialize_metric_call("f_prt_bondbycreditratingtonav", contract, values)
    assert result == "f_prt_bondbycreditratingtonav(000001.OF,LATEST_REPORT_DATE,1,1)"
