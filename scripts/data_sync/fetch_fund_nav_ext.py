#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
补充净值时序 — 用 wss 按月末采样拉取 250 只基金
wsd 不可用，改用 wss tradeDate 逐月获取月末净值
"""
import os, sys, json, datetime
from pathlib import Path

WIND_X64 = r"D:\Wind\Wind.NET.Client\WindNET\x64"
WIND_BIN = r"D:\Wind\Wind.NET.Client\WindNET\bin"
for p in [WIND_X64, WIND_BIN]:
    if p not in sys.path:
        sys.path.insert(0, p)
os.environ["PATH"] = WIND_X64 + ";" + WIND_BIN + ";" + os.environ.get("PATH", "")

DATA_DIR = Path(__file__).parent
EXISTING_DATA = DATA_DIR / "wind_demo_data.json"
OUTPUT_JSON   = DATA_DIR / "wind_fund_nav_ext.json"
OUTPUT_SQL    = DATA_DIR / "wind_fund_nav_ext_dump.sql"

# 月末采样日期 (每月最后一个交易日近似)
SAMPLE_DATES = [
    "20230131", "20230228", "20230331", "20230428", "20230531",
    "20230630", "20230731", "20230831", "20230928", "20231031",
    "20231130", "20231229",
    "20240131", "20240229", "20240329", "20240430", "20240531",
    "20240628", "20240731", "20240830", "20240910",
]

WSS_CHUNK = 80

def safe_float(val, default=0.0):
    try:
        if val is None or str(val).strip() in ('', 'None', 'nan'):
            return default
        return float(val)
    except:
        return default

def safe_date(val):
    if isinstance(val, (datetime.date, datetime.datetime)):
        return val.strftime('%Y-%m-%d')
    s = str(val).strip()
    return s[:10] if len(s) >= 10 else ""

def escape_sql(val):
    if val is None:
        return ''
    return str(val).replace("'", "''")

def main():
    from WindPy import w

    print("=" * 60)
    print("WindPy Fund NAV Extension (Monthly sampling via wss)")
    print("=" * 60)

    with open(EXISTING_DATA, encoding='utf-8') as f:
        existing = json.load(f)

    # 已有净值的基金
    existing_nav_codes = set()
    for nav in existing["nav_history"]:
        existing_nav_codes.add(nav["fund_code"])

    # Top 300 按规模降序
    funds_sorted = sorted(existing["funds"], key=lambda x: -x.get("current_scale_billion", 0))[:300]
    to_fetch = [f for f in funds_sorted if f["fund_code"] not in existing_nav_codes]
    print(f"Need to fetch NAV for {len(to_fetch)} funds ({len(SAMPLE_DATES)} dates each)", flush=True)

    print("\nStarting WindPy...", flush=True)
    w.start()
    if not w.isconnected():
        print("WindPy connection failed!")
        return
    print("  Connected", flush=True)

    all_nav = []
    wind_codes = [f["wind_code"] for f in to_fetch]
    fund_code_map = {f["wind_code"]: f["fund_code"] for f in to_fetch}

    for di, trade_date in enumerate(SAMPLE_DATES):
        # 批量 wss
        for ci in range(0, len(wind_codes), WSS_CHUNK):
            batch = wind_codes[ci:ci+WSS_CHUNK]
            r = w.wss(batch, "nav,NAV_acc", f"tradeDate={trade_date}")
            if r.ErrorCode != 0:
                continue

            for j, wc in enumerate(r.Codes):
                fund_code = fund_code_map.get(wc, wc.split('.')[0])
                unit = safe_float(r.Data[0][j]) if r.Data[0] else 0.0
                acc  = safe_float(r.Data[1][j]) if len(r.Data) > 1 and r.Data[1] else 0.0

                if unit > 0:
                    all_nav.append({
                        "fund_code": fund_code,
                        "nav_date": f"{trade_date[:4]}-{trade_date[4:6]}-{trade_date[6:]}",
                        "unit_nav": round(unit, 4),
                        "accumulated_nav": round(acc, 4),
                        "adjusted_nav": round(acc, 4),  # wss 没有 adjusted，用 acc 代替
                        "daily_growth_rate": 0.0,
                    })

        print(f"  [{di+1}/{len(SAMPLE_DATES)}] {trade_date}: total {len(all_nav)} records", flush=True)

    w.stop()

    print(f"\nTotal NAV records: {len(all_nav)}")

    # 写 JSON
    print("Writing JSON...", flush=True)
    with open(OUTPUT_JSON, "w", encoding="utf-8") as f:
        json.dump({"nav_history": all_nav}, f, ensure_ascii=False, separators=(",", ":"))
    size_mb = os.path.getsize(OUTPUT_JSON) / 1024 / 1024
    print(f"  {OUTPUT_JSON.name}  {size_mb:.2f} MB")

    # 写 SQL
    print("Writing SQL...", flush=True)
    lines = ["-- Fund NAV Extension (Monthly sampling)", ""]
    for n in all_nav:
        lines.append(
            f"INSERT INTO fund_nav_history "
            f"(fund_code, nav_date, unit_nav, accumulated_nav, adjusted_nav, daily_growth_rate) "
            f"VALUES ('{escape_sql(n['fund_code'])}', '{escape_sql(n['nav_date'])}', "
            f"{n['unit_nav']}, {n['accumulated_nav']}, {n['adjusted_nav']}, {n['daily_growth_rate']}) "
            f"ON CONFLICT (fund_code, nav_date) DO NOTHING;"
        )
    with open(OUTPUT_SQL, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    size_mb = os.path.getsize(OUTPUT_SQL) / 1024 / 1024
    print(f"  {OUTPUT_SQL.name}  {size_mb:.2f} MB")

    print("\nDone.")

if __name__ == "__main__":
    main()
