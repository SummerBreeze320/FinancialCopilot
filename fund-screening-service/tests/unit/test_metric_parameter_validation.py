from app.models.parameters import MetricParameterDefinition
from app.pipeline.validator import validate_metric_parameters


def test_validator_fills_default_trade_date() -> None:
    param_def = MetricParameterDefinition(
        key="endDate", valueType="DATE", required=True, defaultValue="LATEST_TRADE_DATE"
    )
    errors, filled, repairs = validate_metric_parameters("f_return_1y", [], [param_def])
    assert errors == []
    assert len(filled) == 1
    assert filled[0]["key"] == "endDate"
    assert filled[0]["value"] == "LATEST_TRADE_DATE"
    assert "DEFAULT_METRIC_PARAMETER" in repairs


def test_validator_rejects_invalid_enum_option() -> None:
    param_def = MetricParameterDefinition(
        key="frequency",
        valueType="ENUM",
        required=False,
        defaultValue="DAILY",
        options=[{"label": "日频", "value": "DAILY"}, {"label": "月频", "value": "MONTHLY"}],
    )
    errors, filled, repairs = validate_metric_parameters(
        "f_nav_return", [{"key": "frequency", "value": "HOURLY"}], [param_def]
    )
    assert any("HOURLY" in err["message"] or err["code"] == "INVALID_METRIC_PARAMETER_ENUM" for err in errors)


def test_validator_rejects_reversed_dates() -> None:
    p1 = MetricParameterDefinition(key="startDate", valueType="DATE", required=True)
    p2 = MetricParameterDefinition(key="endDate", valueType="DATE", required=True)
    input_params = [{"key": "startDate", "value": "2026-09-28"}, {"key": "endDate", "value": "2023-09-28"}]
    errors, filled, repairs = validate_metric_parameters("f_risk_alpha", input_params, [p1, p2])
    assert any(err["code"] == "INVALID_METRIC_DATE_RANGE" for err in errors)


def test_validator_exempts_context_bound_windcode() -> None:
    p_wind = MetricParameterDefinition(key="windCode", valueType="FUND_CODE", required=True)
    errors, filled, repairs = validate_metric_parameters("f_risk_alpha", [], [p_wind])
    assert errors == []
