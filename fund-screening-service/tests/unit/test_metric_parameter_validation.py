import pytest
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


@pytest.mark.asyncio
async def test_graph_end_to_end_fills_default_metric_parameters() -> None:
    from app.models.candidate import CandidateContract
    from app.pipeline.analysis import AnalysisService, RecallPlan
    from app.graph.nodes import ScreeningGraphNodes
    from app.graph.workflow import build_screening_graph
    from app.graph.state import empty_state
    from app.pipeline.builder import CommandBuilder

    p_benchmark = MetricParameterDefinition(
        key="benchmark", valueType="INDEX_CODE", defaultValue="000300.SH"
    )
    metric_contract = CandidateContract(
        domain="metric",
        key="f_risk_alpha",
        name="阿尔法收益",
        aliases=("阿尔法",),
        supported_operators=(">", ">="),
        value_type="PERCENT",
        parameters=(p_benchmark,),
    )

    class DummyMetricRetriever:
        async def search(self, text: str, top_k: int):
            return [metric_contract]

    class EmptyRetriever:
        async def search(self, text: str, top_k: int):
            return []

    async def mock_adjudicator(q, plan, pool):
        return {
            "operator": "AND",
            "groups": [{
                "sectorIds": ["1000019220000000"],
                "metrics": [{"metric": "f_risk_alpha", "operator": ">", "value": 5, "unit": "PERCENT"}],
            }],
            "limit": 20,
        }

    analysis = AnalysisService(
        lambda q: RecallPlan(("metric",)),
        DummyMetricRetriever(),
        EmptyRetriever(),
        EmptyRetriever(),
        mock_adjudicator,
    )
    nodes = ScreeningGraphNodes(analysis, CommandBuilder())
    graph = build_screening_graph(nodes)

    result = await graph.ainvoke(empty_state("阿尔法大于5%或者规模大于10亿"))
    assert result["final_dsl"] is not None
    metric_pred = result["final_dsl"]["children"][0]["metric"]["children"][0]
    assert metric_pred["metric"] == "f_risk_alpha"
    # Parameter default must be filled end-to-end!
    params = metric_pred.get("params", [])
    assert any(p["key"] == "benchmark" and p["value"] == "000300.SH" for p in params)


@pytest.mark.asyncio
async def test_graph_end_to_end_triggers_clarification_on_missing_required_parameter() -> None:
    from app.models.candidate import CandidateContract
    from app.pipeline.analysis import AnalysisService, RecallPlan
    from app.graph.nodes import ScreeningGraphNodes
    from app.graph.workflow import build_screening_graph
    from app.graph.state import empty_state
    from app.pipeline.builder import CommandBuilder

    p_required = MetricParameterDefinition(
        key="customPeriod", valueType="STRING", required=True
    )
    metric_contract = CandidateContract(
        domain="metric",
        key="f_custom_perf",
        name="自定义收益",
        aliases=("自定义收益",),
        supported_operators=(">", ">="),
        value_type="PERCENT",
        parameters=(p_required,),
    )

    class DummyMetricRetriever:
        async def search(self, text: str, top_k: int):
            return [metric_contract]

    class EmptyRetriever:
        async def search(self, text: str, top_k: int):
            return []

    async def mock_adjudicator(q, plan, pool):
        return {
            "operator": "AND",
            "groups": [{
                "sectorIds": ["1000019220000000"],
                "metrics": [{"metric": "f_custom_perf", "operator": ">", "value": 5, "unit": "PERCENT"}],
            }],
            "limit": 20,
        }

    analysis = AnalysisService(
        lambda q: RecallPlan(("metric",)),
        DummyMetricRetriever(),
        EmptyRetriever(),
        EmptyRetriever(),
        mock_adjudicator,
    )
    nodes = ScreeningGraphNodes(analysis, CommandBuilder())
    graph = build_screening_graph(nodes)

    result = await graph.ainvoke(empty_state("自定义收益大于5%或者规模大于10亿"))
    assert result["final_dsl"] is None
    assert result["clarification"] is not None
    assert "MISSING_REQUIRED_METRIC_PARAMETER" in str(result["clarification"])

