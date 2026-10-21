import pytest
from datetime import date
from app.models.candidate import CandidateContract
from app.models.dsl import BusinessGroup, FundScreeningDsl, SectorExpression, SectorLeaf, SortItem
from app.models.dsl_nodes import MetricPredicate, MetricLogicNode
from app.pipeline.validator import validate_and_repair, validate_execution_gate
from app.pipeline.dynamic_defaults import DynamicDefaultResolver


class DummyCalendar:
    def latest_trade_date(self, as_of_date: date) -> date:
        return date(2026, 9, 30)

    def latest_report_date(self, as_of_date: date) -> date:
        return date(2026, 6, 30)


@pytest.fixture
def contracts():
    return {
        "f_info_fundscale": CandidateContract(
            domain="metric",
            key="f_info_fundscale",
            name="基金规模",
            value_type="NUMBER",
            supported_operators=(">", ">=", "<", "<=", "="),
            result_contract={"valueType": "NUMBER", "unit": "元"},
        ),
        "f_return_1y": CandidateContract(
            domain="metric",
            key="f_return_1y",
            name="近1年收益率",
            value_type="NUMBER",
            supported_operators=(">", ">=", "<", "<=", "="),
            result_contract={"valueType": "NUMBER", "unit": "%"},
        ),
    }


default_sector = SectorExpression(operator="OR", children=[SectorLeaf(sector_id="1000002533000000")])


def test_reject_metric_leaf_missing_value(contracts):
    dsl = FundScreeningDsl(
        version="fund-screening/v1",
        operator="AND",
        limit=20,
        children=[
            BusinessGroup(
                sector=default_sector,
                metric=MetricPredicate(metric="f_info_fundscale", operator=">", value=None),
            )
        ],
    )
    res = validate_and_repair(dsl, contracts)
    assert res.status == "REJECT_OR_CLARIFY"
    assert res.clarification.code == "MISSING_METRIC_VALUE"


def test_reject_unit_equals_decimal_value_type(contracts):
    dsl = FundScreeningDsl(
        version="fund-screening/v1",
        operator="AND",
        limit=20,
        children=[
            BusinessGroup(
                sector=default_sector,
                metric=MetricPredicate(metric="f_return_1y", operator=">", value=10, unit="DECIMAL"),
            )
        ],
    )
    res = validate_and_repair(dsl, contracts)
    assert res.status == "REJECT_OR_CLARIFY"
    assert res.clarification.code == "INVALID_METRIC_UNIT"


def test_reject_unresolved_latest_date_placeholder(contracts):
    dsl = FundScreeningDsl(
        version="fund-screening/v1",
        operator="AND",
        limit=20,
        children=[
            BusinessGroup(
                sector=default_sector,
                metric=MetricPredicate(
                    metric="f_return_1y",
                    operator=">",
                    value=10,
                    params=[{"key": "endDate", "value": "LATEST_UNKNOWN_DATE"}],
                ),
            )
        ],
    )
    res = validate_and_repair(dsl, contracts, resolver=DynamicDefaultResolver(DummyCalendar()))
    assert res.status == "REJECT_OR_CLARIFY"
    assert res.clarification.code == "UNRESOLVED_DYNAMIC_DEFAULT"


def test_reject_metric_not_in_contract_index(contracts):
    dsl = FundScreeningDsl(
        version="fund-screening/v1",
        operator="AND",
        limit=20,
        children=[
            BusinessGroup(
                sector=default_sector,
                metric=MetricPredicate(metric="f_unknown_metric_xyz", operator=">", value=10),
            )
        ],
    )
    res = validate_and_repair(dsl, contracts)
    assert res.status == "REJECT_OR_CLARIFY"
    assert res.clarification.code == "UNKNOWN_METRIC"


def test_execution_gate_rejects_missing_group_sort():
    raw_dsl = {
        "version": "fund-screening/v1",
        "operator": "AND",
        "limit": 20,
        "sort": [{"metric": "fund_code", "direction": "ASC"}],
        "children": [
            {
                "limit": 20,
                "sort": [],  # empty group sort
                "metric": {"metric": "f_info_fundscale", "operator": ">", "value": 10},
            }
        ],
    }
    is_valid, errors = validate_execution_gate(raw_dsl)
    assert not is_valid
    assert any(e["code"] == "MISSING_GROUP_SORT" for e in errors)


def test_execution_gate_rejects_missing_root_sort():
    raw_dsl = {
        "version": "fund-screening/v1",
        "operator": "AND",
        "limit": 20,
        "sort": [],  # empty root sort
        "children": [
            {
                "limit": 20,
                "sort": [{"metric": "fund_code", "direction": "ASC"}],
                "metric": {"metric": "f_info_fundscale", "operator": ">", "value": 10},
            }
        ],
    }
    is_valid, errors = validate_execution_gate(raw_dsl)
    assert not is_valid
    assert any(e["code"] == "MISSING_ROOT_SORT" for e in errors)


def test_execution_gate_rejects_missing_group_limit():
    raw_dsl = {
        "version": "fund-screening/v1",
        "operator": "AND",
        "limit": 20,
        "sort": [{"metric": "fund_code", "direction": "ASC"}],
        "children": [
            {
                "limit": None,  # missing limit
                "sort": [{"metric": "fund_code", "direction": "ASC"}],
                "metric": {"metric": "f_info_fundscale", "operator": ">", "value": 10},
            }
        ],
    }
    is_valid, errors = validate_execution_gate(raw_dsl)
    assert not is_valid
    assert any(e["code"] == "MISSING_GROUP_LIMIT" for e in errors)


def test_execution_gate_rejects_sector_leaf_with_match():
    raw_dsl = {
        "version": "fund-screening/v1",
        "operator": "AND",
        "limit": 20,
        "sort": [{"metric": "fund_code", "direction": "ASC"}],
        "children": [
            {
                "limit": 20,
                "sort": [{"metric": "fund_code", "direction": "ASC"}],
                "sector": {"sectorId": "1000002533000000", "match": "SELF_OR_DESCENDANT"},
                "metric": {"metric": "f_info_fundscale", "operator": ">", "value": 10},
            }
        ],
    }
    is_valid, errors = validate_execution_gate(raw_dsl)
    assert not is_valid
    assert any(e["code"] in ("FORBIDDEN_FIELD", "UNKNOWN_SECTOR_FIELD") for e in errors)


def test_collapse_single_child_logic_node(contracts):
    child_pred = MetricPredicate(metric="f_info_fundscale", operator=">", value=10)
    single_child_logic = MetricLogicNode(operator="AND", children=[child_pred])
    root_logic = MetricLogicNode(operator="AND", children=[single_child_logic])
    dsl = FundScreeningDsl(
        version="fund-screening/v1",
        operator="AND",
        limit=20,
        children=[
            BusinessGroup(sector=default_sector, metric=root_logic)
        ],
    )
    res = validate_and_repair(dsl, contracts)
    assert res.status == "REPAIRED"
    assert "COLLAPSE_SINGLE_CHILD_LOGIC" in res.repairs
    assert isinstance(res.dsl.children[0].metric.children[0], MetricPredicate)
    assert res.dsl.children[0].metric.children[0].metric == "f_info_fundscale"
