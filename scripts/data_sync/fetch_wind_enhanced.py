#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WindPy 增强数据拉取脚本 (Demo 完善版)
======================================
为已有基础数据补充四类关键时序/指标数据：

  1. 市场指数行情  (market_index_info + market_index_daily)
  2. 基金业绩指标  (fund_performance) — 从净值数据自行计算
  3. 股票K线数据   (stock_daily_kline)
  4. 宏观经济指标  (macro_indicator)
"""

import os
import sys
import json
import math
import datetime
from pathlib import Path
from collections import defaultdict

# ── WindPy 路径 ──────────────────────────────────────────────────
WIND_X64 = r"D:\Wind\Wind.NET.Client\WindNET\x64"
WIND_BIN = r"D:\Wind\Wind.NET.Client\WindNET\bin"
for p in [WIND_X64, WIND_BIN]:
    if p not in sys.path:
        sys.path.insert(0, p)
os.environ["PATH"] = WIND_X64 + ";" + WIND_BIN + ";" + os.environ.get("PATH", "")

# ── 配置 ────────────────────────────────────────────────────────
START_DATE = "2023-01-01"
END_DATE   = "2024-09-10"
KLINE_TOP  = 100
PERF_TOP   = 200       # 基金业绩计算取 Top 200 (按规模)

DATA_DIR = Path(__file__).parent
EXISTING_DATA = DATA_DIR / "wind_demo_data.json"
MARKET_DATA   = DATA_DIR / "market_basics_data.json"
OUTPUT_JSON   = DATA_DIR / "wind_enhanced_data.json"
OUTPUT_SQL    = DATA_DIR / "wind_enhanced_dump.sql"

# ── 市场指数列表 ────────────────────────────────────────────────
MARKET_INDICES = [
    ("000001.SH", "上证指数",   "SSE",  "综合"),
    ("000300.SH", "沪深300",    "SSE",  "规模"),
    ("000905.SH", "中证500",    "SSE",  "规模"),
    ("000852.SH", "中证1000",   "SSE",  "规模"),
    ("399001.SZ", "深证成指",   "SZSE", "综合"),
    ("399006.SZ", "创业板指",   "SZSE", "板块"),
    ("000688.SH", "科创50",     "SSE",  "板块"),
    ("000016.SH", "上证50",     "SSE",  "规模"),
    ("399005.SZ", "中小100",    "SZSE", "规模"),
    ("881001.WI", "万得全A",    "WI",   "综合"),
]

# ── 宏观指标列表 (Wind EDB 指标代码) ─────────────────────────────
MACRO_INDICATORS = [
    ("M0001227", "CPI同比",           "CPI",  "%",  "月"),
    ("M0001228", "PPI同比",           "PPI",  "%",  "月"),
    ("M0017126", "制造业PMI",         "PMI",  "",   "月"),
    ("M0000617", "M2同比",            "M2",   "%",  "月"),
    ("M5206720", "社融存量同比",       "社融", "%",  "月"),
    ("M0001638", "GDP不变价当季同比",  "GDP",  "%",  "季"),
]

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

def safe_date(val, default='2020-01-01'):
    if isinstance(val, (datetime.date, datetime.datetime)):
        return val.strftime('%Y-%m-%d')
    s = safe_str(val, default)
    return s[:10] if len(s) >= 10 else default

def escape_sql(val):
    if val is None:
        return ''
    return str(val).replace("'", "''")

# ── 1. 市场指数行情 ─────────────────────────────────────────────
def fetch_market_indices(w):
    """拉取主要市场指数的基础信息和日线时序"""
    print("\n[1/4] 拉取市场指数行情...", flush=True)
    indices = []

    for code, name, exchange, category in MARKET_INDICES:
        idx = {
            "index_code": code,
            "index_name": name,
            "exchange": exchange,
            "category": category,
        }

        r = w.wsd(code, "open,high,low,close,volume,amt,pct_chg", START_DATE, END_DATE, "")
        if r.ErrorCode != 0:
            print(f"  [WARN] {code} wsd error: {r.ErrorCode}", flush=True)
            idx["daily"] = []
        else:
            daily = []
            for i, dt in enumerate(r.Times):
                o = safe_float(r.Data[0][i]) if r.Data[0] else 0.0
                h = safe_float(r.Data[1][i]) if len(r.Data) > 1 and r.Data[1] else 0.0
                l = safe_float(r.Data[2][i]) if len(r.Data) > 2 and r.Data[2] else 0.0
                c = safe_float(r.Data[3][i]) if len(r.Data) > 3 and r.Data[3] else 0.0
                v = safe_float(r.Data[4][i]) if len(r.Data) > 4 and r.Data[4] else 0.0
                a = safe_float(r.Data[5][i]) if len(r.Data) > 5 and r.Data[5] else 0.0
                p = safe_float(r.Data[6][i]) if len(r.Data) > 6 and r.Data[6] else 0.0
                if c > 0:
                    daily.append({
                        "index_code": code,
                        "trade_date": safe_date(dt),
                        "open": round(o, 4),
                        "high": round(h, 4),
                        "low": round(l, 4),
                        "close": round(c, 4),
                        "pct_chg": round(p, 4),
                        "volume": round(v, 2),
                        "amount": round(a, 2),
                    })
            idx["daily"] = daily
            print(f"  {code:12s} {name:8s}  {len(daily):4d} days", flush=True)

        indices.append(idx)

    return indices

# ── 2. 基金业绩指标 (从净值数据计算) ─────────────────────────────
def compute_fund_performance(nav_list):
    """从复权净值序列计算业绩指标"""
    if len(nav_list) < 10:
        return None

    # nav_list: [(date_str, adjusted_nav), ...] sorted by date
    navs = [n[1] for n in nav_list]
    last_nav = navs[-1]
    last_date = nav_list[-1][0]

    def return_as_of(days_ago):
        target_idx = len(navs) - 1 - days_ago
        if target_idx < 0:
            return None
        base = navs[target_idx]
        if base == 0:
            return None
        return round((last_nav / base - 1) * 100, 4)

    # 近1月(22交易日)/3月(66)/6月(132)/1年(244)
    r_1m = return_as_of(22)
    r_3m = return_as_of(66)
    r_6m = return_as_of(132)
    r_1y = return_as_of(244)

    # YTD
    ytd_base = None
    for d, n in nav_list:
        if d.startswith("2024-01"):
            ytd_base = n
            break
    r_ytd = round((last_nav / ytd_base - 1) * 100, 4) if ytd_base else None

    # 成立来
    r_inception = round((last_nav / navs[0] - 1) * 100, 4) if navs[0] else None

    # 日收益率序列
    daily_returns = []
    for i in range(1, len(navs)):
        if navs[i - 1] > 0:
            daily_returns.append(navs[i] / navs[i - 1] - 1)

    if len(daily_returns) < 10:
        return {
            "return_1m": r_1m or 0.0, "return_3m": r_3m or 0.0,
            "return_6m": r_6m or 0.0, "return_1y": r_1y or 0.0,
            "return_ytd": r_ytd or 0.0, "return_since_inception": r_inception or 0.0,
            "sharpe_ratio": 0.0, "max_drawdown": 0.0, "volatility": 0.0,
        }

    # 年化夏普比率 (无风险利率 2%)
    mean_r = sum(daily_returns) / len(daily_returns)
    var_r = sum((x - mean_r) ** 2 for x in daily_returns) / (len(daily_returns) - 1)
    std_r = math.sqrt(var_r) if var_r > 0 else 0.0
    rf_daily = 0.02 / 252
    sharpe = (mean_r - rf_daily) / std_r * math.sqrt(252) if std_r > 0 else 0.0

    # 最大回撤
    peak = navs[0]
    max_dd = 0.0
    for n in navs:
        if n > peak:
            peak = n
        dd = (peak - n) / peak if peak > 0 else 0.0
        if dd > max_dd:
            max_dd = dd

    # 年化波动率
    volatility = std_r * math.sqrt(252) * 100

    return {
        "return_1m": r_1m or 0.0,
        "return_3m": r_3m or 0.0,
        "return_6m": r_6m or 0.0,
        "return_1y": r_1y or 0.0,
        "return_ytd": r_ytd or 0.0,
        "return_since_inception": r_inception or 0.0,
        "sharpe_ratio": round(sharpe, 4),
        "max_drawdown": round(max_dd * 100, 4),
        "volatility": round(volatility, 4),
    }

def fetch_fund_performance(w):
    """拉取 Top 200 基金净值，自行计算业绩指标"""
    print(f"\n[2/4] 拉取基金净值计算业绩指标 (Top {PERF_TOP})...", flush=True)

    with open(EXISTING_DATA, encoding='utf-8') as f:
        existing = json.load(f)

    funds = sorted(existing["funds"], key=lambda x: -x.get("current_scale_billion", 0))[:PERF_TOP]
    print(f"  选定 {len(funds)} 只基金 (按规模降序)", flush=True)

    all_perf = []

    for i, fund in enumerate(funds):
        wind_code = fund["wind_code"]
        fund_code = fund["fund_code"]

        # 拉取复权净值
        r = w.wsd(wind_code, "NAV_adj", START_DATE, END_DATE, "")
        if r.ErrorCode != 0 or not r.Data or not r.Data[0]:
            if (i + 1) % 50 == 0:
                print(f"  进度: {i+1}/{len(funds)}", flush=True)
            continue

        nav_list = []
        for j, dt in enumerate(r.Times):
            v = safe_float(r.Data[0][j])
            if v > 0:
                nav_list.append((safe_date(dt), v))

        if len(nav_list) < 10:
            continue

        perf = compute_fund_performance(nav_list)
        if perf:
            perf["fund_code"] = fund_code
            all_perf.append(perf)

        if (i + 1) % 20 == 0:
            print(f"  进度: {i+1}/{len(funds)}", flush=True)

    print(f"  完成: {len(all_perf)} 条业绩指标", flush=True)
    return all_perf

# ── 3. 股票K线数据 ──────────────────────────────────────────────
def fetch_stock_klines(w):
    """拉取 Top 100 股票的日K线数据"""
    print(f"\n[3/4] 拉取股票K线数据 (Top {KLINE_TOP})...", flush=True)

    with open(MARKET_DATA, encoding='utf-8') as f:
        market = json.load(f)

    stocks = sorted(market["stocks"], key=lambda x: -x.get("market_cap_billion", 0))[:KLINE_TOP]
    print(f"  Top {len(stocks)} 股票，按市值降序", flush=True)

    all_klines = []

    for i, stock in enumerate(stocks):
        code = stock["code"]
        name = stock["name"]

        r = w.wsd(code, "open,high,low,close,volume,amt,pct_chg", START_DATE, END_DATE, "")
        if r.ErrorCode != 0:
            print(f"  [WARN] {code} wsd error: {r.ErrorCode}", flush=True)
            continue

        klines = []
        for j, dt in enumerate(r.Times):
            o = safe_float(r.Data[0][j]) if r.Data[0] else 0.0
            h = safe_float(r.Data[1][j]) if len(r.Data) > 1 and r.Data[1] else 0.0
            l = safe_float(r.Data[2][j]) if len(r.Data) > 2 and r.Data[2] else 0.0
            c = safe_float(r.Data[3][j]) if len(r.Data) > 3 and r.Data[3] else 0.0
            v = safe_float(r.Data[4][j]) if len(r.Data) > 4 and r.Data[4] else 0.0
            a = safe_float(r.Data[5][j]) if len(r.Data) > 5 and r.Data[5] else 0.0
            if c > 0:
                klines.append({
                    "stock_code": code,
                    "trade_date": safe_date(dt),
                    "open": round(o, 4),
                    "high": round(h, 4),
                    "low": round(l, 4),
                    "close": round(c, 4),
                    "volume": round(v, 2),
                    "amount": round(a, 2),
                })

        all_klines.append({"stock_code": code, "stock_name": name, "klines": klines})

        if (i + 1) % 10 == 0:
            print(f"  进度: {i+1}/{len(stocks)}", flush=True)

    total_klines = sum(len(s["klines"]) for s in all_klines)
    print(f"  完成: {len(all_klines)} 只股票，共 {total_klines} 条K线", flush=True)
    return all_klines

# ── 4. 宏观经济指标 ─────────────────────────────────────────────
def fetch_macro_indicators(w):
    """拉取宏观经济指标时序数据"""
    print("\n[4/4] 拉取宏观经济指标...", flush=True)
    macros = []

    for code, name, category, unit, freq in MACRO_INDICATORS:
        r = w.edb(code, START_DATE, END_DATE, "")
        if r.ErrorCode != 0:
            print(f"  [WARN] {code} ({name}) edb error: {r.ErrorCode}", flush=True)
            continue

        data_points = []
        for i, dt in enumerate(r.Times):
            val = r.Data[0][i] if r.Data[0] else None
            v = safe_float(val)
            if v != 0.0:
                data_points.append({
                    "indicator_code": code,
                    "indicator_name": name,
                    "category": category,
                    "unit": unit,
                    "frequency": freq,
                    "report_date": safe_date(dt),
                    "value": round(v, 4),
                })

        print(f"  {code:12s} {name:14s}  {len(data_points):4d} records", flush=True)
        macros.extend(data_points)

    print(f"  完成: {len(macros)} 条宏观指标", flush=True)
    return macros

# ── 生成 SQL Dump ───────────────────────────────────────────────
def generate_sql(data):
    lines = []
    lines.append("-- WindPy Enhanced Data SQL Dump")
    lines.append("-- Generated: " + datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
    lines.append("")

    # 1. 市场指数信息表
    lines.append("-- 1. 市场指数信息")
    for idx in data["market_indices"]:
        lines.append(
            f"INSERT INTO market_index_info (index_code, index_name, exchange, category) "
            f"VALUES ('{escape_sql(idx['index_code'])}', '{escape_sql(idx['index_name'])}', "
            f"'{escape_sql(idx['exchange'])}', '{escape_sql(idx['category'])}') "
            f"ON CONFLICT (index_code) DO NOTHING;"
        )

    # 2. 指数日线时序
    lines.append("\n-- 2. 指数日线时序")
    for idx in data["market_indices"]:
        for d in idx["daily"]:
            lines.append(
                f"INSERT INTO market_index_daily (index_code, trade_date, open, high, low, close, pct_chg, volume, amount) "
                f"VALUES ('{escape_sql(d['index_code'])}', '{escape_sql(d['trade_date'])}', "
                f"{d['open']}, {d['high']}, {d['low']}, {d['close']}, {d['pct_chg']}, "
                f"{d['volume']}, {d['amount']}) "
                f"ON CONFLICT (index_code, trade_date) DO NOTHING;"
            )

    # 3. 基金业绩指标
    lines.append("\n-- 3. 基金业绩指标")
    for p in data["fund_performance"]:
        lines.append(
            f"INSERT INTO fund_performance "
            f"(fund_code, return_1m, return_3m, return_6m, return_1y, return_3y, "
            f"return_ytd, return_since_inception, sharpe_ratio, max_drawdown, volatility) "
            f"VALUES ('{escape_sql(p['fund_code'])}', "
            f"{p['return_1m']}, {p['return_3m']}, {p['return_6m']}, "
            f"{p['return_1y']}, {p.get('return_3y', 0.0)}, {p['return_ytd']}, "
            f"{p['return_since_inception']}, {p['sharpe_ratio']}, "
            f"{p['max_drawdown']}, {p['volatility']}) "
            f"ON CONFLICT (fund_code) DO UPDATE SET "
            f"return_1m=EXCLUDED.return_1m, return_3m=EXCLUDED.return_3m, "
            f"return_6m=EXCLUDED.return_6m, return_1y=EXCLUDED.return_1y, "
            f"return_ytd=EXCLUDED.return_ytd, "
            f"return_since_inception=EXCLUDED.return_since_inception, "
            f"sharpe_ratio=EXCLUDED.sharpe_ratio, max_drawdown=EXCLUDED.max_drawdown, "
            f"volatility=EXCLUDED.volatility;"
        )

    # 4. 股票K线数据
    lines.append("\n-- 4. 股票K线数据")
    for stock in data["stock_klines"]:
        for k in stock["klines"]:
            lines.append(
                f"INSERT INTO stock_daily_kline "
                f"(stock_code, trade_date, open, high, low, close, volume, amount) "
                f"VALUES ('{escape_sql(k['stock_code'])}', '{escape_sql(k['trade_date'])}', "
                f"{k['open']}, {k['high']}, {k['low']}, {k['close']}, "
                f"{k['volume']}, {k['amount']}) "
                f"ON CONFLICT (stock_code, trade_date) DO NOTHING;"
            )

    # 5. 宏观经济指标
    lines.append("\n-- 5. 宏观经济指标")
    for m in data["macro_indicators"]:
        lines.append(
            f"INSERT INTO macro_indicator "
            f"(indicator_code, indicator_name, category, unit, frequency, report_date, value) "
            f"VALUES ('{escape_sql(m['indicator_code'])}', '{escape_sql(m['indicator_name'])}', "
            f"'{escape_sql(m['category'])}', '{escape_sql(m['unit'])}', '{escape_sql(m['frequency'])}', "
            f"'{escape_sql(m['report_date'])}', {m['value']}) "
            f"ON CONFLICT (indicator_code, report_date) DO NOTHING;"
        )

    return "\n".join(lines)

# ── 主流程 ──────────────────────────────────────────────────────
def main():
    from WindPy import w

    print("=" * 60)
    print("WindPy Enhanced Data Fetch (Index/Perf/KLine/Macro)")
    print("=" * 60)

    print("\n[0/4] Starting WindPy...", flush=True)
    w.start()
    if not w.isconnected():
        print("  WindPy connection failed!")
        return
    print("  WindPy connected", flush=True)

    data = {}

    # 1. 市场指数
    data["market_indices"] = fetch_market_indices(w)

    # 2. 基金业绩
    data["fund_performance"] = fetch_fund_performance(w)

    # 3. 股票K线
    data["stock_klines"] = fetch_stock_klines(w)

    # 4. 宏观指标
    data["macro_indicators"] = fetch_macro_indicators(w)

    # 统计
    total_index_daily = sum(len(i["daily"]) for i in data["market_indices"])
    total_klines = sum(len(s["klines"]) for s in data["stock_klines"])

    print("\n" + "=" * 60)
    print("Summary:")
    print(f"  Market Indices:  {len(data['market_indices'])} ({total_index_daily} daily)")
    print(f"  Fund Perf:       {len(data['fund_performance'])} funds")
    print(f"  Stock K-lines:   {len(data['stock_klines'])} stocks ({total_klines} records)")
    print(f"  Macro:           {len(data['macro_indicators'])} records")
    print("=" * 60)

    # 写 JSON
    print("\nWriting JSON...", flush=True)
    with open(OUTPUT_JSON, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    size_mb = os.path.getsize(OUTPUT_JSON) / 1024 / 1024
    print(f"  {OUTPUT_JSON.name}  {size_mb:.2f} MB", flush=True)

    # 写 SQL
    print("Writing SQL...", flush=True)
    sql = generate_sql(data)
    with open(OUTPUT_SQL, "w", encoding="utf-8") as f:
        f.write(sql)
    size_mb = os.path.getsize(OUTPUT_SQL) / 1024 / 1024
    print(f"  {OUTPUT_SQL.name}  {size_mb:.2f} MB", flush=True)

    w.stop()
    print("\nDone.")


if __name__ == "__main__":
    main()
