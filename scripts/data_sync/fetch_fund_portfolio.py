#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WindPy 基金多季度持仓 + 净值扩展拉取
=====================================
1. Top 300 基金 × 4 季度 (2023Q3/2023Q4/2024Q1/2024Q2) 前十大重仓
2. 扩展净值时序到 300 只 (补已有 50 只之外的 250 只)
3. 资产配置 (股票/债券市值) — 从 wss prt_stockvalue/prt_bondvalue 获取
4. 行业配置 — 从持仓数据按行业汇总计算
"""

import os
import sys
import json
import datetime
from pathlib import Path
from collections import defaultdict

WIND_X64 = r"D:\Wind\Wind.NET.Client\WindNET\x64"
WIND_BIN = r"D:\Wind\Wind.NET.Client\WindNET\bin"
for p in [WIND_X64, WIND_BIN]:
    if p not in sys.path:
        sys.path.insert(0, p)
os.environ["PATH"] = WIND_X64 + ";" + WIND_BIN + ";" + os.environ.get("PATH", "")

DATA_DIR = Path(__file__).parent
EXISTING_DATA = DATA_DIR / "wind_demo_data.json"
OUTPUT_JSON   = DATA_DIR / "wind_fund_portfolio.json"
OUTPUT_SQL    = DATA_DIR / "wind_fund_portfolio_dump.sql"

FUND_TOP = 300
WSS_CHUNK = 50

# 多季度报表日期
QUARTERS = [
    ("2023Q3", "20230930"),
    ("2023Q4", "20231231"),
    ("2024Q1", "20240331"),
    ("2024Q2", "20240630"),
]

NAV_START = "2023-01-01"
NAV_END   = "2024-09-10"
NAV_CHUNK = 5       # 净值 wsd 每次拉取数量

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

def chunk_list(lst, size):
    for i in range(0, len(lst), size):
        yield lst[i:i + size]

# ── 1. 多季度持仓 ───────────────────────────────────────────────
def fetch_multi_quarter_holdings(w, funds):
    """拉取 Top 300 基金 × 4 季度的前十大重仓"""
    print(f"\n[1/4] 拉取多季度持仓 ({len(funds)} 基金 × {len(QUARTERS)} 季度)...", flush=True)

    all_holdings = []
    all_stocks = {}  # stock_code -> info

    # 加载已有股票行业映射
    with open(EXISTING_DATA, encoding='utf-8') as f:
        existing = json.load(f)
    existing_stock_industry = {}
    for s in existing["stocks"]:
        existing_stock_industry[s["stock_code"]] = s.get("industry", "")

    # 构建基金规模映射 (fund_code -> scale_billion)
    fund_scale = {}
    for f in existing["funds"]:
        fund_scale[f["fund_code"]] = f.get("current_scale_billion", 0)

    for qname, rpt_date in QUARTERS:
        print(f"\n  --- {qname} (rptDate={rpt_date}) ---", flush=True)
        q_count = 0

        for rank in range(1, 11):
            opt = f"order={rank};rptDate={rpt_date}"
            for chunk in chunk_list(funds, WSS_CHUNK):
                codes_str = ",".join(chunk)
                r = w.wss(codes_str,
                    "prt_topstockcode,prt_topstockname,prt_topstockquantity,prt_topstockvalue,prt_topstockwindcode",
                    opt)
                if r.ErrorCode != 0 or not r.Data:
                    continue

                for i, wcode in enumerate(r.Codes):
                    fund_code = wcode.split('.')[0]
                    net_asset = fund_scale.get(fund_code, 0) * 1e8

                    stk_code = safe_str(r.Data[0][i])
                    stk_name = safe_str(r.Data[1][i])
                    qty = safe_float(r.Data[2][i])
                    mkt_val = safe_float(r.Data[3][i])
                    stk_wind = safe_str(r.Data[4][i], stk_code)

                    if not stk_code:
                        continue

                    ratio = round((mkt_val / net_asset) * 100, 2) if net_asset and net_asset > 0 else 0.0
                    shares_w = round(qty / 10000, 2) if qty > 0 else 0.0

                    # 获取行业
                    sector = existing_stock_industry.get(stk_code, "")

                    all_holdings.append({
                        "fund_code": fund_code,
                        "report_quarter": qname,
                        "rank_order": rank,
                        "stock_code": stk_code,
                        "stock_name": stk_name,
                        "stock_wind_code": stk_wind,
                        "holding_ratio": ratio,
                        "holding_shares_ten_thousand": shares_w,
                        "holding_sector": sector,
                    })

                    if stk_code not in all_stocks:
                        exchange = "SSE" if stk_code.startswith("6") else ("SZSE" if stk_code.startswith(("0", "3")) else "HKEX")
                        all_stocks[stk_code] = {
                            "stock_code": stk_code,
                            "stock_name": stk_name,
                            "stock_wind_code": stk_wind,
                            "exchange": exchange,
                            "industry": sector,
                        }
                    q_count += 1

            if rank % 5 == 0:
                print(f"    rank {rank}/10 done", flush=True)

        print(f"  {qname}: {q_count} 条持仓", flush=True)

    # 补充新股票的行业
    new_stocks = {k: v for k, v in all_stocks.items() if k not in existing_stock_industry}
    if new_stocks:
        new_wind_codes = [v["stock_wind_code"] for v in new_stocks.values() if v["stock_wind_code"]]
        print(f"\n  查询 {len(new_wind_codes)} 只新股票行业...", flush=True)
        for chunk in chunk_list(new_wind_codes, 30):
            ind_res = w.wss(",".join(chunk), "industry_sw,sec_name", "industryType=1")
            if ind_res.ErrorCode == 0:
                for j, sw in enumerate(ind_res.Codes):
                    ind = safe_str(ind_res.Data[0][j])
                    code = sw.split('.')[0]
                    if code in all_stocks:
                        all_stocks[code]["industry"] = ind if ind and ind != 'None' else "其他"

        # 回填持仓的行业
        for h in all_holdings:
            if h["stock_code"] in all_stocks:
                h["holding_sector"] = all_stocks[h["stock_code"]]["industry"]

    print(f"\n  总计: {len(all_holdings)} 条持仓, {len(all_stocks)} 只股票", flush=True)
    return all_holdings, all_stocks

# ── 2. 扩展净值时序 ─────────────────────────────────────────────
def fetch_extended_nav(w, funds):
    """补全净值时序到 300 只 (已有 50 只之外的)"""
    print(f"\n[2/4] 扩展净值时序到 {FUND_TOP} 只...", flush=True)

    with open(EXISTING_DATA, encoding='utf-8') as f:
        existing = json.load(f)

    # 找出已有净值的基金代码
    existing_nav_codes = set()
    for nav in existing["nav_history"]:
        existing_nav_codes.add(nav["fund_code"])
    print(f"  已有净值: {len(existing_nav_codes)} 只", flush=True)

    # 需要补拉的
    all_fund_codes = [f.split('.')[0] for f in funds]
    to_fetch = [fc for fc in all_fund_codes if fc not in existing_nav_codes]
    print(f"  需补拉: {len(to_fetch)} 只", flush=True)

    all_nav = []
    fund_wind_map = {f.split('.')[0]: f for f in funds}

    for i, fund_code in enumerate(to_fetch):
        wind_code = fund_wind_map.get(fund_code)
        if not wind_code:
            continue

        r = w.wsd(wind_code, "nav,NAV_acc,NAV_adj", NAV_START, NAV_END, "")
        if r.ErrorCode != 0 or not r.Data or not r.Data[0]:
            continue

        for j, dt in enumerate(r.Times):
            unit = safe_float(r.Data[0][j]) if r.Data[0] else 0.0
            acc  = safe_float(r.Data[1][j]) if len(r.Data) > 1 and r.Data[1] else 0.0
            adj  = safe_float(r.Data[2][j]) if len(r.Data) > 2 and r.Data[2] else 0.0
            if unit > 0:
                # 日涨跌幅
                if j > 0:
                    prev = safe_float(r.Data[0][j-1]) if r.Data[0] else 0.0
                    growth = round((unit / prev - 1) * 100, 4) if prev > 0 else 0.0
                else:
                    growth = 0.0
                all_nav.append({
                    "fund_code": fund_code,
                    "nav_date": safe_date(dt),
                    "unit_nav": round(unit, 4),
                    "accumulated_nav": round(acc, 4),
                    "adjusted_nav": round(adj, 4),
                    "daily_growth_rate": growth,
                })

        if (i + 1) % 20 == 0:
            print(f"  进度: {i+1}/{len(to_fetch)} ({len(all_nav)} 条)", flush=True)

    print(f"  完成: {len(to_fetch)} 只基金, {len(all_nav)} 条净值", flush=True)
    return all_nav

# ── 3. 资产配置 ─────────────────────────────────────────────────
def fetch_asset_allocation(w, funds):
    """拉取 Top 300 基金的资产配置 (股票/债券市值)"""
    print(f"\n[3/4] 拉取资产配置 ({len(funds)} 基金)...", flush=True)

    all_alloc = []

    for qname, rpt_date in QUARTERS:
        print(f"\n  --- {qname} ---", flush=True)
        q_count = 0

        for chunk in chunk_list(funds, WSS_CHUNK):
            codes_str = ",".join(chunk)
            r = w.wss(codes_str, "prt_stockvalue,prt_bondvalue", f"rptDate={rpt_date}")
            if r.ErrorCode != 0:
                continue

            for i, wcode in enumerate(r.Codes):
                fund_code = wcode.split('.')[0]
                stock_val = safe_float(r.Data[0][i]) if r.Data[0] else 0.0
                bond_val  = safe_float(r.Data[1][i]) if len(r.Data) > 1 and r.Data[1] else 0.0

                if stock_val == 0 and bond_val == 0:
                    continue

                all_alloc.append({
                    "fund_code": fund_code,
                    "report_quarter": qname,
                    "stock_value": round(stock_val, 2),
                    "bond_value": round(bond_val, 2),
                })
                q_count += 1

        print(f"    {qname}: {q_count} 条", flush=True)

    print(f"\n  总计: {len(all_alloc)} 条资产配置", flush=True)
    return all_alloc

# ── 4. 行业配置 (从持仓汇总) ────────────────────────────────────
def compute_industry_allocation(holdings):
    """从持仓数据按季度汇总行业配置"""
    print(f"\n[4/4] 从持仓汇总行业配置...", flush=True)

    # 按 fund_code + report_quarter 分组
    fund_quarter = defaultdict(lambda: defaultdict(float))
    fund_quarter_total = defaultdict(float)

    for h in holdings:
        key = (h["fund_code"], h["report_quarter"])
        sector = h["holding_sector"] or "其他"
        ratio = h["holding_ratio"]
        fund_quarter[key][sector] += ratio
        fund_quarter_total[key] += ratio

    all_ind_alloc = []
    for (fund_code, quarter), sectors in fund_quarter.items():
        total = fund_quarter_total[(fund_code, quarter)]
        if total == 0:
            continue
        for sector, ratio in sorted(sectors.items(), key=lambda x: -x[1]):
            all_ind_alloc.append({
                "fund_code": fund_code,
                "report_quarter": quarter,
                "industry": sector,
                "holding_ratio": round(ratio, 2),
                "ratio_pct": round(ratio / total * 100, 2) if total > 0 else 0.0,
            })

    print(f"  完成: {len(all_ind_alloc)} 条行业配置", flush=True)
    return all_ind_alloc

# ── 生成 SQL ────────────────────────────────────────────────────
def generate_sql(data):
    lines = []
    lines.append("-- WindPy Fund Portfolio Data (Multi-quarter holdings + NAV + Asset allocation)")
    lines.append(f"-- Generated: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append("")

    # 1. 多季度持仓
    lines.append("-- 1. 多季度前十大重仓")
    for h in data["holdings"]:
        lines.append(
            f"INSERT INTO fund_quarterly_holdings "
            f"(fund_code, report_quarter, rank_order, stock_code, stock_name, holding_ratio, holding_shares_ten_thousand, holding_sector) "
            f"VALUES ('{escape_sql(h['fund_code'])}', '{escape_sql(h['report_quarter'])}', "
            f"{h['rank_order']}, '{escape_sql(h['stock_code'])}', '{escape_sql(h['stock_name'])}', "
            f"{h['holding_ratio']}, {h['holding_shares_ten_thousand']}, '{escape_sql(h['holding_sector'])}') "
            f"ON CONFLICT (fund_code, report_quarter, stock_code) DO NOTHING;"
        )

    # 2. 新股票
    lines.append("\n-- 2. 新股票档案")
    for s in data["new_stocks"].values():
        lines.append(
            f"INSERT INTO stock_info (stock_code, stock_name, exchange, industry) "
            f"VALUES ('{escape_sql(s['stock_code'])}', '{escape_sql(s['stock_name'])}', "
            f"'{escape_sql(s['exchange'])}', '{escape_sql(s['industry'])}') "
            f"ON CONFLICT (stock_code) DO NOTHING;"
        )

    # 3. 扩展净值
    lines.append("\n-- 3. 扩展净值时序")
    for n in data["nav_history"]:
        lines.append(
            f"INSERT INTO fund_nav_history "
            f"(fund_code, nav_date, unit_nav, accumulated_nav, adjusted_nav, daily_growth_rate) "
            f"VALUES ('{escape_sql(n['fund_code'])}', '{escape_sql(n['nav_date'])}', "
            f"{n['unit_nav']}, {n['accumulated_nav']}, {n['adjusted_nav']}, {n['daily_growth_rate']}) "
            f"ON CONFLICT (fund_code, nav_date) DO NOTHING;"
        )

    # 4. 资产配置
    lines.append("\n-- 4. 基金资产配置")
    for a in data["asset_allocation"]:
        lines.append(
            f"INSERT INTO fund_asset_allocation "
            f"(fund_code, report_quarter, stock_value, bond_value) "
            f"VALUES ('{escape_sql(a['fund_code'])}', '{escape_sql(a['report_quarter'])}', "
            f"{a['stock_value']}, {a['bond_value']}) "
            f"ON CONFLICT (fund_code, report_quarter) DO NOTHING;"
        )

    # 5. 行业配置
    lines.append("\n-- 5. 基金行业配置")
    for ind in data["industry_allocation"]:
        lines.append(
            f"INSERT INTO fund_industry_allocation "
            f"(fund_code, report_quarter, industry, holding_ratio, ratio_pct) "
            f"VALUES ('{escape_sql(ind['fund_code'])}', '{escape_sql(ind['report_quarter'])}', "
            f"'{escape_sql(ind['industry'])}', {ind['holding_ratio']}, {ind['ratio_pct']}) "
            f"ON CONFLICT (fund_code, report_quarter, industry) DO NOTHING;"
        )

    return "\n".join(lines)

# ── 主流程 ──────────────────────────────────────────────────────
def main():
    from WindPy import w

    print("=" * 60)
    print("WindPy Fund Portfolio Fetch (Multi-quarter + NAV + Asset/Industry)")
    print("=" * 60)

    # 加载已有数据
    with open(EXISTING_DATA, encoding='utf-8') as f:
        existing = json.load(f)

    # 按规模降序取 Top 300
    funds_sorted = sorted(existing["funds"], key=lambda x: -x.get("current_scale_billion", 0))[:FUND_TOP]
    fund_wind_codes = [f["wind_code"] for f in funds_sorted]
    print(f"Selected {len(fund_wind_codes)} funds by scale", flush=True)

    # 启动 WindPy
    print("\nStarting WindPy...", flush=True)
    w.start()
    if not w.isconnected():
        print("WindPy connection failed!")
        return
    print("  Connected", flush=True)

    data = {}

    # 1. 多季度持仓
    holdings, new_stocks = fetch_multi_quarter_holdings(w, fund_wind_codes)
    data["holdings"] = holdings
    data["new_stocks"] = new_stocks

    # 2. 扩展净值
    data["nav_history"] = fetch_extended_nav(w, fund_wind_codes)

    # 3. 资产配置
    data["asset_allocation"] = fetch_asset_allocation(w, fund_wind_codes)

    # 4. 行业配置
    data["industry_allocation"] = compute_industry_allocation(holdings)

    w.stop()

    # 统计
    print("\n" + "=" * 60)
    print("Summary:")
    print(f"  Multi-quarter Holdings:  {len(data['holdings'])} records")
    print(f"  New Stocks:              {len(data['new_stocks'])} stocks")
    print(f"  Extended NAV:            {len(data['nav_history'])} records")
    print(f"  Asset Allocation:        {len(data['asset_allocation'])} records")
    print(f"  Industry Allocation:     {len(data['industry_allocation'])} records")
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

    print("\nDone.")


if __name__ == "__main__":
    main()
