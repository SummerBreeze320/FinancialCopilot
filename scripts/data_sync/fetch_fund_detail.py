#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WindPy 基金扩展基础数据拉取 (Top 300)
=====================================
为 Top 300 基金（按规模降序）拉取扩展基础信息：
  - 最新净值 (unit_nav, accumulated_nav, nav_date)
  - 投资风格 (invest_style)
  - 风险等级 (risk_level)
  - 最新净资产 (net_asset_yuan)
  - 成立日期 (setup_date)
  - 投资策略文本 (invest_strategy) — 仅 Top 50
  - 扩展业绩指标 (近1月/3月/6月/1年收益) — 从净值计算补全到 300 只
"""

import os
import sys
import json
import math
import datetime
from pathlib import Path

WIND_X64 = r"D:\Wind\Wind.NET.Client\WindNET\x64"
WIND_BIN = r"D:\Wind\Wind.NET.Client\WindNET\bin"
for p in [WIND_X64, WIND_BIN]:
    if p not in sys.path:
        sys.path.insert(0, p)
os.environ["PATH"] = WIND_X64 + ";" + WIND_BIN + ";" + os.environ.get("PATH", "")

DATA_DIR = Path(__file__).parent
EXISTING_DATA = DATA_DIR / "wind_demo_data.json"
OUTPUT_JSON   = DATA_DIR / "wind_fund_detail.json"
OUTPUT_SQL    = DATA_DIR / "wind_fund_detail_dump.sql"

FUND_TOP = 300
STRATEGY_TOP = 50       # 投资策略文本仅拉 Top 50 (文本太长)
NAV_START = "2023-01-01"
NAV_END   = "2024-09-10"
WSS_CHUNK = 80

# ── 工具函数 ────────────────────────────────────────────────────
def safe_str(val, default=''):
    if val is None or str(val).strip() in ('', 'None', 'nan'):
        return default
    return str(val).strip()

def safe_float(val, default=0.0):
    try:
        if val is None or str(val).strip() in ('', 'None', 'nan'):
            return default
        return float(val)
    except (TypeError, ValueError):
        return default

def safe_date(val, default=''):
    if isinstance(val, (datetime.date, datetime.datetime)):
        return val.strftime('%Y-%m-%d')
    s = safe_str(val, default)
    return s[:10] if len(s) >= 10 else default

def escape_sql(val):
    if val is None:
        return ''
    return str(val).replace("'", "''").replace("\\", "\\\\")

def compute_perf_from_nav(nav_list):
    """从复权净值序列计算业绩指标"""
    if len(nav_list) < 10:
        return None
    navs = [n[1] for n in nav_list]
    last_nav = navs[-1]

    def ret(days_ago):
        idx = len(navs) - 1 - days_ago
        if idx < 0 or navs[idx] == 0:
            return 0.0
        return round((last_nav / navs[idx] - 1) * 100, 4)

    daily_returns = []
    for i in range(1, len(navs)):
        if navs[i - 1] > 0:
            daily_returns.append(navs[i] / navs[i - 1] - 1)

    if len(daily_returns) < 10:
        return {
            "return_1m": ret(22), "return_3m": ret(66),
            "return_6m": ret(132), "return_1y": ret(244),
            "sharpe_ratio": 0.0, "max_drawdown": 0.0, "volatility": 0.0,
        }

    mean_r = sum(daily_returns) / len(daily_returns)
    var_r = sum((x - mean_r) ** 2 for x in daily_returns) / (len(daily_returns) - 1)
    std_r = math.sqrt(var_r) if var_r > 0 else 0.0
    rf_daily = 0.02 / 252
    sharpe = (mean_r - rf_daily) / std_r * math.sqrt(252) if std_r > 0 else 0.0

    peak = navs[0]
    max_dd = 0.0
    for n in navs:
        if n > peak:
            peak = n
        dd = (peak - n) / peak if peak > 0 else 0.0
        if dd > max_dd:
            max_dd = dd

    volatility = std_r * math.sqrt(252) * 100

    return {
        "return_1m": ret(22),
        "return_3m": ret(66),
        "return_6m": ret(132),
        "return_1y": ret(244),
        "sharpe_ratio": round(sharpe, 4),
        "max_drawdown": round(max_dd * 100, 4),
        "volatility": round(volatility, 4),
    }


def main():
    from WindPy import w

    print("=" * 60)
    print(f"WindPy Fund Detail Fetch (Top {FUND_TOP})")
    print("=" * 60)

    # 加载已有数据
    with open(EXISTING_DATA, encoding='utf-8') as f:
        existing = json.load(f)

    funds = sorted(existing["funds"], key=lambda x: -x.get("current_scale_billion", 0))[:FUND_TOP]
    print(f"Selected {len(funds)} funds by scale", flush=True)

    # 启动 WindPy
    print("\nStarting WindPy...", flush=True)
    w.start()
    if not w.isconnected():
        print("WindPy connection failed!")
        return
    print("  Connected", flush=True)

    # ── 1. wss 批量拉取扩展基础字段 ──────────────────────────
    print(f"\n[1/3] Fetching extended basic fields...", flush=True)
    WSS_FIELDS = "sec_name,fund_investtype,prt_netasset,fund_setupdate,nav,NAV_acc,nav_date,fund_investstyle,fund_risklevel"

    all_details = []
    wind_codes = [f["wind_code"] for f in funds]

    for i in range(0, len(wind_codes), WSS_CHUNK):
        batch = wind_codes[i:i+WSS_CHUNK]
        r = w.wss(batch, WSS_FIELDS, "")
        if r.ErrorCode != 0:
            print(f"  [WARN] batch {i//WSS_CHUNK+1} error: {r.ErrorCode}", flush=True)
            continue

        for j, wc in enumerate(batch):
            fund_code = wc.replace(".OF", "")
            detail = {
                "fund_code": fund_code,
                "fund_name": safe_str(r.Data[0][j]) if r.Data[0] else "",
                "invest_type": safe_str(r.Data[1][j]) if len(r.Data) > 1 and r.Data[1] else "",
                "net_asset_yuan": safe_float(r.Data[2][j]) if len(r.Data) > 2 and r.Data[2] else 0.0,
                "setup_date": safe_date(r.Data[3][j]) if len(r.Data) > 3 and r.Data[3] else "",
                "latest_nav": round(safe_float(r.Data[4][j]), 4) if len(r.Data) > 4 and r.Data[4] else 0.0,
                "latest_acc_nav": round(safe_float(r.Data[5][j]), 4) if len(r.Data) > 5 and r.Data[5] else 0.0,
                "latest_nav_date": safe_date(r.Data[6][j]) if len(r.Data) > 6 and r.Data[6] else "",
                "invest_style": safe_str(r.Data[7][j]) if len(r.Data) > 7 and r.Data[7] else "",
                "risk_level": safe_str(r.Data[8][j]) if len(r.Data) > 8 and r.Data[8] else "",
            }
            all_details.append(detail)

        if (i // WSS_CHUNK + 1) % 2 == 0:
            print(f"  Progress: {i+len(batch)}/{len(wind_codes)}", flush=True)

    print(f"  Done: {len(all_details)} funds", flush=True)

    # ── 2. 投资策略文本 (Top 50) ──────────────────────────────
    print(f"\n[2/3] Fetching investment strategy (Top {STRATEGY_TOP})...", flush=True)
    strategy_codes = wind_codes[:STRATEGY_TOP]

    for i in range(0, len(strategy_codes), WSS_CHUNK):
        batch = strategy_codes[i:i+WSS_CHUNK]
        r = w.wss(batch, "fund_investstrategy", "")
        if r.ErrorCode != 0:
            print(f"  [WARN] strategy batch error: {r.ErrorCode}", flush=True)
            continue

        for j, wc in enumerate(batch):
            fund_code = wc.replace(".OF", "")
            strategy = safe_str(r.Data[0][j]) if r.Data[0] else ""
            # 找到对应的 detail 并追加
            for d in all_details:
                if d["fund_code"] == fund_code:
                    # 截取前 2000 字符
                    d["invest_strategy"] = strategy[:2000] if strategy else ""
                    break

        print(f"  Progress: {i+len(batch)}/{len(strategy_codes)}", flush=True)

    has_strategy = sum(1 for d in all_details if d.get("invest_strategy"))
    print(f"  Done: {has_strategy} funds with strategy", flush=True)

    # ── 3. 业绩指标 (从净值计算，补全到 300) ──────────────────
    print(f"\n[3/3] Computing performance from NAV (Top {FUND_TOP})...", flush=True)

    # 加载已有的 performance 数据（200 只已有）
    enhanced_path = DATA_DIR / "wind_enhanced_data.json"
    existing_perf = {}
    if enhanced_path.exists():
        with open(enhanced_path, encoding='utf-8') as f:
            enhanced = json.load(f)
        for p in enhanced.get("fund_performance", []):
            existing_perf[p["fund_code"]] = p

    new_perf_count = 0
    for i, fund in enumerate(funds):
        fund_code = fund["fund_code"]
        wind_code = fund["wind_code"]

        if fund_code in existing_perf:
            continue

        # 拉取复权净值
        r = w.wsd(wind_code, "NAV_adj", NAV_START, NAV_END, "")
        if r.ErrorCode != 0 or not r.Data or not r.Data[0]:
            continue

        nav_list = []
        for j, dt in enumerate(r.Times):
            v = safe_float(r.Data[0][j])
            if v > 0:
                nav_list.append((safe_date(dt), v))

        if len(nav_list) < 10:
            continue

        perf = compute_perf_from_nav(nav_list)
        if perf:
            perf["fund_code"] = fund_code
            existing_perf[fund_code] = perf
            new_perf_count += 1

        if (i + 1) % 20 == 0:
            print(f"  Progress: {i+1}/{len(funds)} (new: {new_perf_count})", flush=True)

    all_perf = list(existing_perf.values())
    print(f"  Done: {new_perf_count} new, {len(all_perf)} total performance records", flush=True)

    w.stop()

    # ── 写 JSON ──────────────────────────────────────────────
    data = {
        "fund_details": all_details,
        "fund_performance": all_perf,
    }

    print("\nWriting JSON...", flush=True)
    with open(OUTPUT_JSON, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    size_mb = os.path.getsize(OUTPUT_JSON) / 1024 / 1024
    print(f"  {OUTPUT_JSON.name}  {size_mb:.2f} MB", flush=True)

    # ── 写 SQL ───────────────────────────────────────────────
    print("Writing SQL...", flush=True)
    lines = []
    lines.append("-- WindPy Fund Detail Data")
    lines.append(f"-- Generated: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append("")

    # fund_detail 表 (UPSERT)
    lines.append("-- 1. 基金扩展基础信息")
    for d in all_details:
        strategy = escape_sql(d.get("invest_strategy", ""))
        lines.append(
            f"INSERT INTO fund_detail "
            f"(fund_code, fund_name, invest_type, net_asset_yuan, setup_date, "
            f"latest_nav, latest_acc_nav, latest_nav_date, invest_style, risk_level, invest_strategy) "
            f"VALUES ('{escape_sql(d['fund_code'])}', '{escape_sql(d['fund_name'])}', "
            f"'{escape_sql(d['invest_type'])}', {d['net_asset_yuan']}, "
            f"'{escape_sql(d['setup_date'])}', {d['latest_nav']}, {d['latest_acc_nav']}, "
            f"'{escape_sql(d['latest_nav_date'])}', '{escape_sql(d['invest_style'])}', "
            f"'{escape_sql(d['risk_level'])}', '{strategy}') "
            f"ON CONFLICT (fund_code) DO UPDATE SET "
            f"fund_name=EXCLUDED.fund_name, invest_type=EXCLUDED.invest_type, "
            f"net_asset_yuan=EXCLUDED.net_asset_yuan, setup_date=EXCLUDED.setup_date, "
            f"latest_nav=EXCLUDED.latest_nav, latest_acc_nav=EXCLUDED.latest_acc_nav, "
            f"latest_nav_date=EXCLUDED.latest_nav_date, invest_style=EXCLUDED.invest_style, "
            f"risk_level=EXCLUDED.risk_level, invest_strategy=EXCLUDED.invest_strategy;"
        )

    # fund_performance (UPSERT)
    lines.append("\n-- 2. 基金业绩指标 (补全到300)")
    for p in all_perf:
        lines.append(
            f"INSERT INTO fund_performance "
            f"(fund_code, return_1m, return_3m, return_6m, return_1y, "
            f"return_ytd, return_since_inception, sharpe_ratio, max_drawdown, volatility) "
            f"VALUES ('{escape_sql(p['fund_code'])}', "
            f"{p.get('return_1m', 0.0)}, {p.get('return_3m', 0.0)}, "
            f"{p.get('return_6m', 0.0)}, {p.get('return_1y', 0.0)}, "
            f"{p.get('return_ytd', 0.0)}, {p.get('return_since_inception', 0.0)}, "
            f"{p.get('sharpe_ratio', 0.0)}, {p.get('max_drawdown', 0.0)}, "
            f"{p.get('volatility', 0.0)}) "
            f"ON CONFLICT (fund_code) DO UPDATE SET "
            f"return_1m=EXCLUDED.return_1m, return_3m=EXCLUDED.return_3m, "
            f"return_6m=EXCLUDED.return_6m, return_1y=EXCLUDED.return_1y, "
            f"return_ytd=EXCLUDED.return_ytd, "
            f"return_since_inception=EXCLUDED.return_since_inception, "
            f"sharpe_ratio=EXCLUDED.sharpe_ratio, max_drawdown=EXCLUDED.max_drawdown, "
            f"volatility=EXCLUDED.volatility;"
        )

    with open(OUTPUT_SQL, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    size_mb = os.path.getsize(OUTPUT_SQL) / 1024 / 1024
    print(f"  {OUTPUT_SQL.name}  {size_mb:.2f} MB", flush=True)

    # ── 统计 ──────────────────────────────────────────────────
    print("\n" + "=" * 60)
    print("Summary:")
    print(f"  Fund Details:     {len(all_details)} funds")
    print(f"  With Strategy:    {has_strategy} funds")
    print(f"  Performance:      {len(all_perf)} funds ({new_perf_count} new)")
    print("=" * 60)
    print("\nDone.")


if __name__ == "__main__":
    main()
