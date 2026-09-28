from __future__ import annotations

import os
import pytest
from pathlib import Path

from app.catalog.loader import Catalog
from app.models.dsl import FundScreeningDsl
from app.pipeline.builder import CommandBuilder
from app.pipeline.function_serializer import serialize_metric_call
from app.pipeline.validator import validate_and_repair


@pytest.fixture(scope="module")
def catalog():
    service_root = Path(__file__).resolve().parents[2]
    metrics_path = service_root.parent / "docs" / "temp" / "metrics.json"
    sectors_path = service_root / "tests" / "fixtures" / "sectors-small.json"
    entities_path = service_root / "tests" / "fixtures" / "entities-small.json"
    return Catalog.from_files(metrics_path, sectors_path, entities_path)


def test_completion_report_date_parameter(catalog):
    """1. 报告期参数补全：测试默认值补全及显式指定报告期"""
    contract = catalog.metric_contract("f_prt_bondbycreditratingtonav")
    assert contract is not None

    # 包含必填枚举，但省略带有默认值的 reportDate
    values = {
        "windCode": "000001.OF",
        "ratingTerm": 1,
        "creditRating": 1,
    }
    call_str = serialize_metric_call("f_prt_bondbycreditratingtonav", contract, values)
    assert call_str == "f_prt_bondbycreditratingtonav(000001.OF,LATEST_REPORT_DATE,1,1)"

    # 显式指定报告期
    values["reportDate"] = "20260630"
    call_str_explicit = serialize_metric_call("f_prt_bondbycreditratingtonav", contract, values)
    assert call_str_explicit == "f_prt_bondbycreditratingtonav(000001.OF,20260630,1,1)"


def test_completion_interval_parameter(catalog):
    """2. 区间参数补全：测试 startDate 与 endDate 的配对与校验"""
    contract = catalog.metric_contract("f_nav_adjustedreturn")
    assert contract is not None

    values = {
        "windCode": "000001.OF",
        "startDate": "20240101",
        "endDate": "20241231",
    }
    call_str = serialize_metric_call("f_nav_adjustedreturn", contract, values)
    assert call_str == "f_nav_adjustedreturn(000001.OF,20240101,20241231)"

    # 省略具有默认值的 endDate
    del values["endDate"]
    call_str_def = serialize_metric_call("f_nav_adjustedreturn", contract, values)
    assert call_str_def == "f_nav_adjustedreturn(000001.OF,20240101,LATEST_TRADE_DATE)"

    # 逆序区间必须拒绝
    values_invalid = {
        "windCode": "000001.OF",
        "startDate": "20250101",
        "endDate": "20240101",
    }
    with pytest.raises(ValueError, match="startDate must be <= endDate"):
        serialize_metric_call("f_nav_adjustedreturn", contract, values_invalid)


def test_completion_benchmark_parameter(catalog):
    """3. 基准参数补全：测试业绩比较基准参数的默认补全与显式代码"""
    contract = catalog.metric_contract("f_risk_beta")
    assert contract is not None

    # 省略 benchmark 和 endDate
    values = {
        "windCode": "000001.OF",
        "startDate": "20240101",
    }
    call_str = serialize_metric_call("f_risk_beta", contract, values)
    assert call_str == "f_risk_beta(000001.OF,20240101,LATEST_TRADE_DATE,FUND_BENCHMARK)"

    # 显式指定基准
    values["indexCode"] = "000300.SH"
    call_str_explicit = serialize_metric_call("f_risk_beta", contract, values)
    assert call_str_explicit == "f_risk_beta(000001.OF,20240101,LATEST_TRADE_DATE,000300.SH)"


def test_completion_rank_parameter(catalog):
    """4. 排名参数补全：测试持仓排名参数校验与生成"""
    contract = catalog.metric_contract("f_prt_topstockname")
    assert contract is not None

    values = {
        "windCode": "000001.OF",
        "rank": 1,
    }
    call_str = serialize_metric_call("f_prt_topstockname", contract, values)
    assert call_str == "f_prt_topstockname(000001.OF,LATEST_REPORT_DATE,1)"

    # 缺少排名必填参数必须拒绝
    del values["rank"]
    with pytest.raises(ValueError, match="rank"):
        serialize_metric_call("f_prt_topstockname", contract, values)


def test_completion_enum_parameter(catalog):
    """5. 枚举参数补全：测试合法枚举值接受与非法枚举值拒绝"""
    contract = catalog.metric_contract("f_prt_bondbycreditratingtonav")
    assert contract is not None

    # 合法枚举整数 (ratingTerm: 1=长期, creditRating: 1=AAA)
    values = {
        "windCode": "000001.OF",
        "ratingTerm": 1,
        "creditRating": 1,
    }
    call_str = serialize_metric_call("f_prt_bondbycreditratingtonav", contract, values)
    assert call_str == "f_prt_bondbycreditratingtonav(000001.OF,LATEST_REPORT_DATE,1,1)"

    # 非法枚举值
    values["ratingTerm"] = 999
    with pytest.raises(ValueError, match="ratingTerm"):
        serialize_metric_call("f_prt_bondbycreditratingtonav", contract, values)


def test_pipeline_validation_and_repair_for_contracts(catalog):
    """全流水线校验与修复：验证默认参数注入与 storageConvention"""
    contracts_map = {m.key: m for m in catalog.metrics}

    # 1. 最大回撤负数修复
    max_downside_contract = catalog.metric_contract("f_risk_maxdownside")
    dsl_drawdown = FundScreeningDsl.model_validate({
        "version": "fund-screening/v1",
        "operator": "AND",
        "children": [{
            "sector": {"operator": "OR", "children": [{"sectorId": "1000019220000000", "match": "SELF_OR_DESCENDANT"}]},
            "metric": {
                "operator": "AND",
                "children": [{
                    "metric": "f_risk_maxdownside",
                    "operator": "<",
                    "value": 15,
                    "unit": "%",
                }],
            },
        }],
        "limit": 20,
    })
    res_drawdown = validate_and_repair(dsl_drawdown, contracts_map)
    # 当缺少 startDate 时，必须产生澄清请求
    assert res_drawdown.clarification is not None
    assert res_drawdown.clarification.code == "MISSING_REQUIRED_METRIC_PARAMETER"

    # 当提供 startDate 时，自动补齐 endDate=LATEST_TRADE_DATE 并修复负数回撤
    dsl_drawdown_with_date = FundScreeningDsl.model_validate({
        "version": "fund-screening/v1",
        "operator": "AND",
        "children": [{
            "sector": {"operator": "OR", "children": [{"sectorId": "1000019220000000", "match": "SELF_OR_DESCENDANT"}]},
            "metric": {
                "operator": "AND",
                "children": [{
                    "metric": "f_risk_maxdownside",
                    "operator": "<",
                    "value": 15,
                    "unit": "%",
                    "params": [{"key": "startDate", "value": "20240101"}],
                }],
            },
        }],
        "limit": 20,
    })
    res_repaired = validate_and_repair(dsl_drawdown_with_date, contracts_map)
    assert res_repaired.clarification is None
    assert "NEGATIVE_DRAWDOWN_STORAGE" in res_repaired.repairs
    assert "DEFAULT_METRIC_PARAMETER" in res_repaired.repairs
    pred = res_repaired.dsl.children[0].metric.children[0]
    assert pred.value == -15
    assert pred.operator == ">="
    # 验证 endDate 被默认补全
    param_dict = {p["key"]: p["value"] for p in pred.params}
    assert param_dict["endDate"] == "LATEST_TRADE_DATE"

    # 2. 缺少必填参数触发 Clarification
    dsl_missing_param = FundScreeningDsl.model_validate({
        "version": "fund-screening/v1",
        "operator": "AND",
        "children": [{
            "sector": {"operator": "OR", "children": [{"sectorId": "1000019220000000", "match": "SELF_OR_DESCENDANT"}]},
            "metric": {
                "operator": "AND",
                "children": [{
                    "metric": "f_prt_bondbycreditratingtonav",
                    "operator": ">",
                    "value": 10,
                    "unit": "%",
                    # 缺少 ratingTerm / creditRating
                    "params": [],
                }],
            },
        }],
        "limit": 20,
    })
    res_missing = validate_and_repair(dsl_missing_param, contracts_map)
    assert res_missing.clarification is not None
    assert res_missing.clarification.code == "MISSING_REQUIRED_METRIC_PARAMETER"


@pytest.mark.skipif(
    os.getenv("RUN_REAL_FUND_SCREENING_TESTS") != "1",
    reason="Requires live external services. Set RUN_REAL_FUND_SCREENING_TESTS=1 to run.",
)
@pytest.mark.asyncio
async def test_real_llm_parameter_completion_smoke():
    from app.config import Settings
    from app.runtime import build_production_compiler

    settings = Settings()
    compiler = build_production_compiler(settings)

    # 覆盖真实 LLM 提取参数能力
    query = "近1年收益率大于15%且规模大于10亿"
    result = await compiler.compile(query)
    assert result.dsl is not None
