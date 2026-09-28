import re

from app.models.dsl import FundScreeningDsl


ACTIVE_EQUITY_SECTORS = ("1000002533000000", "1000002535000000")
DEFAULT_SECTOR = "1000019220000000"


def compile_mvp_query(query: str) -> FundScreeningDsl:
    """编译 MVP 支持的高置信自然语言模式，其他输入使用最小默认示例。"""
    if "主动权益" in query and "最大回撤" in query and "规模" in query:
        drawdown = _number_before(query, "%", 15)
        scale = _number_before(query, "亿", 10)
        limit = _number_after(query, "前", 20)
        return FundScreeningDsl.model_validate({
            "version": "fund-screening/v1",
            "operator": "AND",
            "children": [{
                "sector": {"operator": "OR", "children": [
                    {"sectorId": sector_id, "match": "SELF_OR_DESCENDANT"}
                    for sector_id in ACTIVE_EQUITY_SECTORS
                ]},
                "metric": {"operator": "AND", "children": [
                    {"metric": "f_risk_maxdownside", "operator": "<", "value": drawdown, "unit": "PERCENT"},
                    {"metric": "f_info_fundscale", "operator": ">", "value": scale, "unit": "BILLION_CNY"},
                ]},
            }],
            "sort": [{"metric": "f_return_3y", "direction": "DESC", "nulls": "LAST"}],
            "limit": limit,
        })
    return FundScreeningDsl.model_validate({
        "version": "fund-screening/v1", "operator": "AND",
        "children": [{
            "sector": {"operator": "OR", "children": [{"sectorId": DEFAULT_SECTOR}]},
            "metric": {"operator": "AND", "children": [
                {"metric": "f_info_existingyear", "operator": ">", "value": 10, "unit": "YEAR"}
            ]},
        }],
        "limit": 20,
    })


def _number_before(text: str, suffix: str, default: float) -> float:
    matches = re.findall(rf"(\d+(?:\.\d+)?)\s*{re.escape(suffix)}", text)
    return float(matches[-1]) if matches else default


def _number_after(text: str, prefix: str, default: int) -> int:
    match = re.search(rf"{re.escape(prefix)}\s*(\d+)\s*只?", text)
    return int(match.group(1)) if match else default
