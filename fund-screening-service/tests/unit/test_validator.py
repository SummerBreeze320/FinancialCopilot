from app.models.dsl import FundScreeningDsl
from app.models.candidate import CandidateContract
from app.pipeline.validator import validate_and_repair


def test_validator_normalizes_positive_drawdown_threshold_to_negative() -> None:
    dsl = FundScreeningDsl.model_validate({"version":"fund-screening/v1","operator":"AND","children":[{"sector":{"operator":"OR","children":[{"sectorId":"1000019220000000"}]},"metric":{"operator":"AND","children":[{"metric":"f_risk_maxdownside","operator":"<","value":15,"unit":"PERCENT"}]}}],"limit":20})
    result = validate_and_repair(dsl)
    leaf = result.dsl.children[0].metric.children[0]
    assert (leaf.operator, leaf.value) == (">=", -15)


def test_validator_returns_clarification_for_unknown_sort_metric() -> None:
    dsl = FundScreeningDsl.model_validate({"version":"fund-screening/v1","operator":"AND","children":[{"sector":{"operator":"OR","children":[{"sectorId":"1000019220000000"}]},"metric":{"operator":"AND","children":[{"metric":"f_return_3y","operator":">","value":1,"unit":"PERCENT"}]}}],"sort":[{"metric":"unknown","direction":"DESC"}],"limit":20})
    result = validate_and_repair(dsl, {"f_return_3y": CandidateContract("metric", "f_return_3y", "近3年回报", supported_usage=("filter", "sort"))})
    assert result.clarification.code == "UNKNOWN_SORT_METRIC"


def test_validator_adds_deterministic_sort_for_limited_group() -> None:
    dsl = FundScreeningDsl.model_validate({"version":"fund-screening/v1","operator":"AND","children":[{"sector":{"operator":"OR","children":[{"sectorId":"1000019220000000"}]},"metric":{"operator":"AND","children":[{"metric":"f_return_3y","operator":">","value":1,"unit":"PERCENT"}]},"limit":10}],"limit":20})
    result = validate_and_repair(dsl)
    assert result.dsl.children[0].sort[0].metric == "fund_code"
