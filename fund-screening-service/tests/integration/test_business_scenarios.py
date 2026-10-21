from __future__ import annotations

from pathlib import Path
import re
import pytest

from app.catalog.loader import Catalog
from app.models.dsl import FundScreeningDsl
from app.pipeline.function_serializer import serialize_metric_call
from app.pipeline.validator import validate_and_repair


@pytest.fixture(scope="module")
def catalog():
    service_root = Path(__file__).resolve().parents[2]
    metrics_path = service_root.parent / "docs" / "temp" / "metrics.json"
    sectors_path = service_root / "tests" / "fixtures" / "sectors-small.json"
    entities_path = service_root / "tests" / "fixtures" / "entities-small.json"
    return Catalog.from_files(metrics_path, sectors_path, entities_path)


@pytest.fixture(scope="module")
def contracts_map(catalog):
    return {m.key: m for m in catalog.metrics}


def _make_dsl(metric: str, operator: str, value: any, unit: str | None = None, params: list | None = None) -> FundScreeningDsl:
    return FundScreeningDsl.model_validate({
        "version": "fund-screening/v1",
        "operator": "AND",
        "children": [{
            "sector": {"operator": "OR", "children": [{"sectorId": "1000019220000000", "match": "SELF_OR_DESCENDANT"}]},
            "metric": {
                "operator": "AND",
                "children": [{
                    "metric": metric,
                    "operator": operator,
                    "value": value,
                    "unit": unit,
                    "params": params or [],
                }],
            },
        }],
        "limit": 20,
    })


def test_scenario_1_fixed_window_return(catalog, contracts_map):
    """1. 固定窗口收益：包含 windCode 和 endDate（默认 LATEST_TRADE_DATE），直接合法并序列化"""
    contract = catalog.metric_contract("f_return_1y")
    assert contract is not None
    assert len(contract.parameters) == 2
    assert contract.parameters[0].key == "windCode"
    assert contract.parameters[1].key == "endDate"
    assert contract.parameters[1].default_value == "LATEST_TRADE_DATE"

    dsl = _make_dsl("f_return_1y", ">", 10.0, "%")
    val_res = validate_and_repair(dsl, contracts_map)
    assert val_res.clarification is None
    pred = val_res.dsl.children[0].metric.children[0]
    p_map = {p["key"]: p["value"] for p in pred.params}
    p_map["windCode"] = "000001.OF"
    call_str = serialize_metric_call(pred.metric, contract, p_map)
    assert re.match(r"^f_return_1y\(000001\.OF,\d{8}\)$", call_str)



def test_scenario_2_arbitrary_interval_return(catalog, contracts_map):
    """2. 任意区间收益：支持显式指定 startDate 与 endDate"""
    contract = catalog.metric_contract("f_nav_adjustedreturn")
    assert contract is not None

    dsl = _make_dsl(
        "f_nav_adjustedreturn",
        ">",
        15.5,
        "%",
        params=[{"key": "startDate", "value": "20240101"}, {"key": "endDate", "value": "20241231"}],
    )
    val_res = validate_and_repair(dsl, contracts_map)
    assert val_res.clarification is None
    pred = val_res.dsl.children[0].metric.children[0]
    params = {p["key"]: p["value"] for p in pred.params}
    params["windCode"] = "000001.OF"
    call_str = serialize_metric_call(pred.metric, contract, params)
    assert call_str == "f_nav_adjustedreturn(000001.OF,20240101,20241231)"


def test_scenario_3_max_drawdown_negative_storage(catalog, contracts_map):
    """3. 最大回撤：根据 storageConvention=NEGATIVE_PERCENT 翻转符号与比较符"""
    contract = catalog.metric_contract("f_risk_maxdownside")
    assert contract is not None
    assert contract.result_contract.get("storageConvention") == "NEGATIVE_PERCENT"

    dsl = _make_dsl(
        "f_risk_maxdownside",
        "<",
        12.5,
        "%",
        params=[{"key": "startDate", "value": "20240101"}],
    )
    val_res = validate_and_repair(dsl, contracts_map)
    assert val_res.clarification is None
    assert "NEGATIVE_DRAWDOWN_STORAGE" in val_res.repairs
    pred = val_res.dsl.children[0].metric.children[0]
    assert pred.value == -12.5
    assert pred.operator == ">="


def test_scenario_4_beta_benchmark_default_and_custom(catalog, contracts_map):
    """4. Beta 基准：支持默认 FUND_BENCHMARK 与显式指数代码"""
    contract = catalog.metric_contract("f_risk_beta")
    assert contract is not None

    # 默认基准
    dsl_default = _make_dsl("f_risk_beta", "<", 1.0, params=[{"key": "startDate", "value": "20240101"}])
    res_def = validate_and_repair(dsl_default, contracts_map)
    assert res_def.clarification is None
    pred_def = res_def.dsl.children[0].metric.children[0]
    p_map = {p["key"]: p["value"] for p in pred_def.params}
    p_map["windCode"] = "000001.OF"
    call_def = serialize_metric_call(pred_def.metric, contract, p_map)
    assert re.match(r"^f_risk_beta\(000001\.OF,20240101,\d{8},FUND_BENCHMARK\)$", call_def)

    # 自定义基准
    dsl_custom = _make_dsl(
        "f_risk_beta",
        "<",
        1.0,
        params=[{"key": "startDate", "value": "20240101"}, {"key": "indexCode", "value": "000300.SH"}],
    )
    res_cust = validate_and_repair(dsl_custom, contracts_map)
    assert res_cust.clarification is None
    pred_cust = res_cust.dsl.children[0].metric.children[0]
    p_map_c = {p["key"]: p["value"] for p in pred_cust.params}
    p_map_c["windCode"] = "000001.OF"
    call_cust = serialize_metric_call(pred_cust.metric, contract, p_map_c)
    assert re.match(r"^f_risk_beta\(000001\.OF,20240101,\d{8},000300\.SH\)$", call_cust)


def test_scenario_5_top_stock_rank(catalog, contracts_map):
    """5. 重仓股 rank：校验必需排名参数及默认报告期补全"""
    contract = catalog.metric_contract("f_prt_topstockname")
    assert contract is not None

    dsl = _make_dsl("f_prt_topstockname", "=", "贵州茅台", params=[{"key": "rank", "value": 1}])
    val_res = validate_and_repair(dsl, contracts_map)
    assert val_res.clarification is None
    pred = val_res.dsl.children[0].metric.children[0]
    p_map = {p["key"]: p["value"] for p in pred.params}
    p_map["windCode"] = "000001.OF"
    call_str = serialize_metric_call(pred.metric, contract, p_map)
    assert re.match(r"^f_prt_topstockname\(000001\.OF,\d{8},1\)$", call_str)


def test_scenario_6_bond_credit_rating_dual_enum(catalog, contracts_map):
    """6. 信用评级双枚举：ratingTerm 与 creditRating 必须为合法整数枚举映射"""
    contract = catalog.metric_contract("f_prt_bondbycreditratingtonav")
    assert contract is not None

    dsl = _make_dsl(
        "f_prt_bondbycreditratingtonav",
        ">",
        5.0,
        "%",
        params=[{"key": "ratingTerm", "value": 1}, {"key": "creditRating", "value": 1}],
    )
    val_res = validate_and_repair(dsl, contracts_map)
    assert val_res.clarification is None
    pred = val_res.dsl.children[0].metric.children[0]
    p_map = {p["key"]: p["value"] for p in pred.params}
    p_map["windCode"] = "000001.OF"
    call_str = serialize_metric_call(pred.metric, contract, p_map)
    assert re.match(r"^f_prt_bondbycreditratingtonav\(000001\.OF,\d{8},1,1\)$", call_str)


def test_scenario_7_report_date_explicit_vs_default(catalog, contracts_map):
    """7. 报告期：默认补全 LATEST_REPORT_DATE，或显式接收指定季度报告期"""
    contract = catalog.metric_contract("f_prt_totalasset")
    assert contract is not None

    # 默认补全
    dsl_def = _make_dsl("f_prt_totalasset", ">", 100000000, "元")
    res_def = validate_and_repair(dsl_def, contracts_map)
    assert res_def.clarification is None
    pred_def = res_def.dsl.children[0].metric.children[0]
    p_map_def = {p["key"]: p["value"] for p in pred_def.params}
    assert re.match(r"^\d{8}$", p_map_def["reportDate"])

    # 显式指定
    dsl_exp = _make_dsl("f_prt_totalasset", ">", 100000000, "元", params=[{"key": "reportDate", "value": "20240930"}])
    res_exp = validate_and_repair(dsl_exp, contracts_map)
    assert res_exp.clarification is None
    pred_exp = res_exp.dsl.children[0].metric.children[0]
    p_map_exp = {p["key"]: p["value"] for p in pred_exp.params}
    assert p_map_exp["reportDate"] == "20240930"



def test_scenario_8_latest_trade_date_default(catalog, contracts_map):
    """8. 最新交易日默认值：只给 startDate 时自动补齐 endDate=LATEST_TRADE_DATE"""
    contract = catalog.metric_contract("f_nav_adjustedreturn")
    dsl = _make_dsl("f_nav_adjustedreturn", ">", 10.0, "%", params=[{"key": "startDate", "value": "20240101"}])
    val_res = validate_and_repair(dsl, contracts_map)
    assert val_res.clarification is None
    pred = val_res.dsl.children[0].metric.children[0]
    p_map = {p["key"]: p["value"] for p in pred.params}
    assert re.match(r"^\d{8}$", p_map["endDate"])


def test_scenario_9_multi_turn_parameter_clarification(catalog, contracts_map):
    """9. 多轮补参：首轮缺少必填参数触发澄清，下一轮补齐参数后成功通过校验与序列化"""
    contract = catalog.metric_contract("f_risk_beta")

    # Round 1: missing required startDate
    dsl_round_1 = _make_dsl("f_risk_beta", "<", 1.0)
    res_round_1 = validate_and_repair(dsl_round_1, contracts_map)
    assert res_round_1.clarification is not None
    assert res_round_1.clarification.code == "MISSING_REQUIRED_METRIC_PARAMETER"
    assert "startDate" in res_round_1.clarification.message

    # Round 2: user supplies startDate
    dsl_round_2 = _make_dsl("f_risk_beta", "<", 1.0, params=[{"key": "startDate", "value": "20240101"}])
    res_round_2 = validate_and_repair(dsl_round_2, contracts_map)
    assert res_round_2.clarification is None
    pred = res_round_2.dsl.children[0].metric.children[0]
    p_map = {p["key"]: p["value"] for p in pred.params}
    p_map["windCode"] = "000001.OF"
    call_str = serialize_metric_call(pred.metric, contract, p_map)
    assert re.match(r"^f_risk_beta\(000001\.OF,20240101,\d{8},FUND_BENCHMARK\)$", call_str)


def test_scenario_10_illegal_enum_clarification(catalog, contracts_map):
    """10. 非法枚举澄清：传入不存在的枚举选项时直接产生澄清请求"""
    dsl = _make_dsl(
        "f_prt_bondbycreditratingtonav",
        ">",
        5.0,
        "%",
        params=[{"key": "ratingTerm", "value": 999}, {"key": "creditRating", "value": 1}],
    )
    val_res = validate_and_repair(dsl, contracts_map)
    assert val_res.clarification is not None
    assert val_res.clarification.code == "INVALID_METRIC_PARAMETER_ENUM"
    assert "ratingTerm" in val_res.clarification.message


def test_scenario_11_unsupported_operator_clarification(catalog, contracts_map):
    """11. 不支持的运算符：ENUM / STRING 结果指标禁止使用大小比较符"""
    dsl = _make_dsl("f_info_risklevel", ">", 2)
    val_res = validate_and_repair(dsl, contracts_map)
    assert val_res.clarification is not None
    assert val_res.clarification.code == "UNSUPPORTED_OPERATOR"
