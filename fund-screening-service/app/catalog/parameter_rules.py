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
