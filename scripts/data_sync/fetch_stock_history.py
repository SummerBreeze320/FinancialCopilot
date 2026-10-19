#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AkShare 股票历史数据拉取脚本
=============================
拉取A股代表性标的的:
  1. 日K线行情 (stock_zh_a_hist)
  2. 季度财务摘要 (stock_financial_abstract_ths)
  3. 分红送转历史 (stock_history_dividend_detail)
  4. 十大股东 (stock_gdfx_top_10)
生成 JSON + SQL dump 文件。
"""

import os
import sys
import json
import datetime
import time
from pathlib import Path

try:
    import akshare as ak
    import pandas as pd
except ImportError:
    print("请先安装依赖: pip install -r requirements.txt")
    sys.exit(1)

OUTPUT_DIR = Path(__file__).parent

TARGET_STOCKS = [
    ("600519", "600519.SH", "贵州茅台"),
    ("000858", "000858.SZ", "五粮液"),
    ("002415", "002415.SZ", "海康威视"),
    ("000333", "000333.SZ", "美的集团"),
    ("300750", "300750.SZ", "宁德时代"),
    ("600276", "600276.SH", "恒瑞医药"),
    ("603259", "603259.SH", "药明康德"),
    ("300760", "300760.SZ", "迈瑞医疗"),
    ("000661", "000661.SZ", "长春高新"),
    ("000001", "000001.SZ", "平安银行"),
]


def safe_val(val, default=0.0):
    if val is None or str(val).strip() in ('', 'None', 'nan', '-'):
        return default
    try:
        return round(float(val), 4)
    except (TypeError, ValueError):
        return default


def fetch_daily_kline(symbol, code_full, name, start="20220101"):
    """拉取日K线数据"""
    try:
        df = ak.stock_zh_a_hist(symbol=symbol, period="daily",
                                start_date=start, end_date=datetime.date.today().strftime("%Y%m%d"),
                                adjust="qfq")
        if df is None or df.empty:
            return []
        records = []
        for _, row in df.iterrows():
            records.append({
                "stock_code": code_full,
                "trade_date": str(row.get("日期", "")),
                "open": safe_val(row.get("开盘")),
                "high": safe_val(row.get("最高")),
                "low": safe_val(row.get("最低")),
                "close": safe_val(row.get("收盘")),
                "volume": safe_val(row.get("成交量")),
                "amount": safe_val(row.get("成交额")),
            })
        print(f"  {name}({code_full}): {len(records)} 条K线", flush=True)
        return records
    except Exception as e:
        print(f"  {name}({code_full}) K线拉取失败: {e}", flush=True)
        return []


def fetch_financial_report(symbol, code_full, name):
    """拉取季度财务摘要"""
    try:
        df = ak.stock_financial_abstract_ths(symbol=symbol, indicator="按报告期")
        if df is None or df.empty:
            return []
        records = []
        for _, row in df.head(8).iterrows():
            report_date_str = str(row.get("报告期", ""))
            quarter = ""
            if "2024" in report_date_str:
                if "06-30" in report_date_str or "二季度" in report_date_str:
                    quarter = "2024Q2"
                elif "03-31" in report_date_str or "一季度" in report_date_str:
                    quarter = "2024Q1"
                elif "09-30" in report_date_str or "三季度" in report_date_str:
                    quarter = "2024Q3"
                elif "12-31" in report_date_str:
                    quarter = "2024Q4"
            elif "2023" in report_date_str:
                if "12-31" in report_date_str:
                    quarter = "2023Q4"
                elif "09-30" in report_date_str:
                    quarter = "2023Q3"
            if not quarter:
                continue
            records.append({
                "stock_code": code_full,
                "report_quarter": quarter,
                "revenue_billion": safe_val(row.get("营业总收入"), 0),
                "net_profit_billion": safe_val(row.get("净利润"), 0),
                "eps": safe_val(row.get("基本每股收益"), 0),
                "roe": safe_val(row.get("净资产收益率"), 0),
                "gross_margin": safe_val(row.get("销售毛利率"), 0),
                "net_margin": safe_val(row.get("销售净利率"), 0),
                "total_assets_billion": safe_val(row.get("总资产"), 0),
                "debt_to_asset_ratio": safe_val(row.get("资产负债率"), 0),
            })
        print(f"  {name}({code_full}): {len(records)} 条财务数据", flush=True)
        return records
    except Exception as e:
        print(f"  {name}({code_full}) 财务数据拉取失败: {e}", flush=True)
        return []


def fetch_dividends(symbol, code_full, name):
    """拉取分红送转历史"""
    try:
        df = ak.stock_history_dividend_detail(symbol=symbol, indicator="分红")
        if df is None or df.empty:
            return []
        records = []
        for _, row in df.head(5).iterrows():
            year = str(row.get("报告期", ""))[:4]
            if not year or year == "nan":
                continue
            records.append({
                "stock_code": code_full,
                "dividend_year": year,
                "cash_dividend_per_10": safe_val(row.get("派现"), 0),
                "bonus_shares_per_10": safe_val(row.get("送股"), 0),
                "transfer_shares_per_10": safe_val(row.get("转增"), 0),
                "ex_dividend_date": str(row.get("除权除息日", "")),
                "record_date": str(row.get("股权登记日", "")),
                "announcement_date": str(row.get("公告日", "")),
            })
        print(f"  {name}({code_full}): {len(records)} 条分红数据", flush=True)
        return records
    except Exception as e:
        print(f"  {name}({code_full}) 分红数据拉取失败: {e}", flush=True)
        return []


def fetch_top_shareholders(symbol, code_full, name):
    """拉取十大股东"""
    try:
        df = ak.stock_gdfx_top_10(symbol=symbol)
        if df is None or df.empty:
            return []
        records = []
        latest_date = df.iloc[0].get("日期", "")
        latest = df[df["日期"] == latest_date].head(10) if latest_date else df.head(10)
        for idx, (_, row) in enumerate(latest.iterrows()):
            records.append({
                "stock_code": code_full,
                "report_quarter": "2024Q2",
                "rank_order": idx + 1,
                "shareholder_name": str(row.get("股东名称", "未知")),
                "shareholder_type": "机构",
                "holding_shares_ten_thousand": safe_val(row.get("持股数量(万股)"), 0),
                "holding_ratio": safe_val(row.get("持股比例(%)"), 0),
                "holding_change": str(row.get("增减", "未知")),
            })
        print(f"  {name}({code_full}): {len(records)} 条股东数据", flush=True)
        return records
    except Exception as e:
        print(f"  {name}({code_full}) 股东数据拉取失败: {e}", flush=True)
        return []


def escape_sql(val):
    if val is None:
        return ''
    return str(val).replace("'", "''")


def generate_sql(all_klines, all_financials, all_dividends, all_shareholders):
    """生成 SQL dump"""
    sql_path = OUTPUT_DIR / "stock_history_dump.sql"
    with open(sql_path, "w", encoding="utf-8") as f:
        f.write(f"-- AkShare 股票历史数据 SQL Dump\n")
        f.write(f"-- 生成时间: {datetime.datetime.now()}\n\n")

        f.write("-- === 日K线 ===\n")
        for k in all_klines:
            f.write(
                f"INSERT INTO stock_daily_kline (stock_code, trade_date, open, high, low, close, volume, amount) "
                f"VALUES ('{k['stock_code']}', '{k['trade_date']}', {k['open']}, {k['high']}, "
                f"{k['low']}, {k['close']}, {k['volume']}, {k['amount']}) "
                f"ON CONFLICT DO NOTHING;\n"
            )

        f.write("\n-- === 季度财务 ===\n")
        for fin in all_financials:
            cols = ", ".join(fin.keys())
            vals = ", ".join(f"'{v}'" if isinstance(v, str) else str(v) for v in fin.values())
            f.write(f"INSERT INTO stock_financial_quarterly ({cols}) VALUES ({vals}) ON CONFLICT DO NOTHING;\n")

        f.write("\n-- === 分红历史 ===\n")
        for d in all_dividends:
            cols = ", ".join(d.keys())
            vals = ", ".join(f"'{v}'" if isinstance(v, str) else str(v) for v in d.values())
            f.write(f"INSERT INTO stock_dividend_history ({cols}) VALUES ({vals}) ON CONFLICT DO NOTHING;\n")

        f.write("\n-- === 十大股东 ===\n")
        for sh in all_shareholders:
            cols = ", ".join(sh.keys())
            vals = ", ".join(f"'{escape_sql(v)}'" if isinstance(v, str) else str(v) for v in sh.values())
            f.write(f"INSERT INTO stock_top_shareholders ({cols}) VALUES ({vals}) ON CONFLICT DO NOTHING;\n")

    print(f"\n[SQL] {sql_path} ({sql_path.stat().st_size / 1024:.0f} KB)")
    return sql_path


def main():
    print("=" * 60)
    print("AkShare 股票历史数据拉取")
    print("=" * 60)

    all_klines = []
    all_financials = []
    all_dividends = []
    all_shareholders = []

    for symbol, code_full, name in TARGET_STOCKS:
        print(f"\n>>> 处理 {name} ({code_full})...", flush=True)
        all_klines.extend(fetch_daily_kline(symbol, code_full, name))
        time.sleep(0.3)
        all_financials.extend(fetch_financial_report(symbol, code_full, name))
        time.sleep(0.3)
        all_dividends.extend(fetch_dividends(symbol, code_full, name))
        time.sleep(0.3)
        all_shareholders.extend(fetch_top_shareholders(symbol, code_full, name))
        time.sleep(0.5)

    # 生成 JSON
    json_path = OUTPUT_DIR / "stock_history_data.json"
    data = {
        "meta": {
            "generated_at": datetime.datetime.now().isoformat(),
            "source": "AkShare",
            "stock_count": len(TARGET_STOCKS),
            "kline_count": len(all_klines),
            "financial_count": len(all_financials),
            "dividend_count": len(all_dividends),
            "shareholder_count": len(all_shareholders),
        },
        "klines": all_klines,
        "financials": all_financials,
        "dividends": all_dividends,
        "shareholders": all_shareholders,
    }
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print(f"\n[JSON] {json_path} ({json_path.stat().st_size / 1024:.0f} KB)")

    # 生成 SQL
    sql_path = generate_sql(all_klines, all_financials, all_dividends, all_shareholders)

    print("\n" + "=" * 60)
    print(f"完成! K线 {len(all_klines)} | 财务 {len(all_financials)} | "
          f"分红 {len(all_dividends)} | 股东 {len(all_shareholders)}")
    print("=" * 60)


if __name__ == "__main__":
    main()
