from app.models.dsl import FundScreeningDsl
from app.pipeline.validator import validate_and_repair


def test_validator_normalizes_positive_drawdown_threshold_to_negative() -> None:
    dsl = FundScreeningDsl.model_validate({"version":"fund-screening/v1","operator":"AND","children":[{"sector":{"operator":"OR","children":[{"sectorId":"1000019220000000"}]},"metric":{"operator":"AND","children":[{"metric":"f_risk_maxdownside","operator":"<","value":15,"unit":"PERCENT"}]}}],"limit":20})
    result = validate_and_repair(dsl)
    leaf = result.dsl.children[0].metric.children[0]
    assert (leaf.operator, leaf.value) == (">=", -15)
