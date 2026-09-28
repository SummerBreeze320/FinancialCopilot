import pytest
from pydantic import ValidationError

from app.models.dsl import BusinessGroup, FundScreeningDsl, MetricExpression


PREDICATE = {"metric": "f_return_3y", "operator": ">=", "value": 30, "unit": "PERCENT"}
EXAMPLE_WITH_TWO_RETURN_PREDICATES = {
    "version": "fund-screening/v1",
    "operator": "AND",
    "children": [{
        "sector": {"operator": "OR", "children": [{"sectorId": "1000002533000000", "match": "SELF_OR_DESCENDANT"}]},
        "metric": {"operator": "AND", "children": [
            PREDICATE,
            {"operator": "OR", "children": [PREDICATE, {**PREDICATE, "value": 50}]},
        ]},
    }],
    "sort": [],
    "limit": 20,
}


def test_dsl_allows_duplicate_metric_with_nested_or() -> None:
    dsl = FundScreeningDsl.model_validate(EXAMPLE_WITH_TWO_RETURN_PREDICATES)

    assert dsl.children[0].metric.operator == "AND"


def test_business_group_requires_sector_and_metric() -> None:
    with pytest.raises(ValidationError):
        BusinessGroup.model_validate({"metric": {"operator": "AND", "children": [PREDICATE]}})


def test_not_requires_exactly_one_child() -> None:
    with pytest.raises(ValidationError):
        MetricExpression.model_validate({"operator": "NOT", "children": [PREDICATE, PREDICATE]})
