from __future__ import annotations

from typing import Any


def infer_parameters(metric: dict[str, Any]) -> list[dict[str, Any]]:
    m = metric.get("mnemonic", "")
    params: list[dict[str, Any]] = [
        {"key": "windCode", "valueType": "FUND_CODE", "required": True}
    ]
    if m in ("f_risk_alpha", "f_risk_beta", "f_risk_treynor", "f_risk_sharpe", "f_risk_informationratio", "f_risk_jensen"):
        params.extend([
            {"key": "startDate", "valueType": "DATE", "required": True},
            {"key": "endDate", "valueType": "DATE", "required": True, "defaultValue": "LATEST_TRADE_DATE"},
            {"key": "indexCode", "valueType": "INDEX_CODE", "required": False, "defaultValue": "FUND_BENCHMARK"},
        ])
    elif m.startswith("f_risk_"):
        params.extend([
            {"key": "startDate", "valueType": "DATE", "required": True},
            {"key": "endDate", "valueType": "DATE", "required": True, "defaultValue": "LATEST_TRADE_DATE"},
        ])
    elif m.startswith("f_prt_") or "holder" in m or "style" in m or m.startswith("f_netasset_"):
        params.append({"key": "reportDate", "valueType": "DATE", "required": True, "defaultValue": "LATEST_REPORT_DATE"})
    elif "return" in m or m.startswith("f_nav_") or m.startswith("f_mmf_") or m.startswith("f_div_") or m.startswith("f_dq_") or m.startswith("f_pq_"):
        params.append({"key": "endDate", "valueType": "DATE", "required": True, "defaultValue": "LATEST_TRADE_DATE"})
    return params


def infer_value_type(metric: dict[str, Any]) -> str:
    m = metric.get("mnemonic", "")
    name = metric.get("index_name") or metric.get("name") or ""
    if metric.get("value_type"):
        return metric["value_type"]

    if "fundscale" in m or "totalasset" in m or "netasset" in m or "规模" in name or "资产总值" in name:
        return "BILLION_CNY"
    if (
        "return" in m
        or "maxdownside" in m
        or "ratio" in m
        or "yield" in m
        or "sharpe" in m
        or "pct" in m
        or "stdev" in m
        or "vol" in m
        or "回报" in name
        or "收益" in name
        or "回撤" in name
        or "率" in name
    ):
        return "PERCENT"
    if "existingyear" in m or "workingyears" in m or "ptmyear" in m or "年限" in name or "存续年" in name:
        return "YEAR"
    if "date" in m or "日期" in name:
        return "DATE"
    if "code" in m or "代码" in name:
        return "ENTITY_REF"
    return "NUMBER"


def infer_aliases(metric: dict[str, Any]) -> list[str]:
    m = metric.get("mnemonic", "")
    name = metric.get("index_name") or metric.get("name") or ""
    existing = list(metric.get("aliases") or [])
    aliases = set(existing)

    if m == "f_info_fundscale" or "规模" in name:
        aliases.update(["规模", "基金规模", "合并规模", "资产规模"])
    elif m == "f_info_existingyear" or "成立年限" in name:
        aliases.update(["成立", "成立年限", "成立时间", "成立多久", "存续年限", "存续时间"])
    elif m == "f_risk_maxdownside" or "最大回撤" in name:
        aliases.update(["回撤", "最大回撤", "回撤率", "最大回撤率"])
    elif m == "f_return_3y" or "近3年回报" in name:
        aliases.update(["近3年收益", "近三年收益", "近3年回报", "近三年回报", "三年收益"])
    elif m == "f_return_1y" or "近1年回报" in name:
        aliases.update(["近1年收益", "近一年收益", "近1年回报", "近一年回报", "一年收益"])
    elif m == "f_return_ytd" or "今年以来回报" in name:
        aliases.update(["今年以来收益", "今年收益", "年内收益", "年初至今收益"])
    elif m == "f_info_windcode" or "Wind代码" in name:
        aliases.update(["基金代码", "代码"])

    return sorted(aliases)

