#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""股票字段名探测 - wsd 和 wss 带参数"""

import os, sys
WIND_X64 = r"D:\Wind\Wind.NET.Client\WindNET\x64"
WIND_BIN = r"D:\Wind\Wind.NET.Client\WindNET\bin"
for p in [WIND_X64, WIND_BIN]:
    if p not in sys.path:
        sys.path.insert(0, p)
os.environ["PATH"] = WIND_X64 + ";" + WIND_BIN + ";" + os.environ.get("PATH", "")

from WindPy import w
w.start()

code = "600519.SH"

# wsd 测试 (时序数据)
print("=== wsd 市值和行业 ===")
fields = ["mkt_cap_total", "mkt_cap_float", "mkt_cap_ar", "industry_sw", "industry_sw1", "industry_zx", "dividend_yield2"]
for field in fields:
    r = w.wsd(code, field, "2024-09-10", "2024-09-10", "")
    if r.ErrorCode == 0 and r.Data and r.Data[0] and r.Data[0][0] is not None:
        print(f"  wsd {field:30s} -> OK: {r.Data[0][0]}")
    else:
        print(f"  wsd {field:30s} -> FAIL (err={r.ErrorCode}, data={r.Data})")

# wss 带参数
print("\n=== wss 带参数 ===")
params_to_try = [
    "tradeDate=20240910",
    "priceType=F",
    "tradeDate=20240910;priceType=F",
]
for param in params_to_try:
    for field in ["mkt_cap_total", "industry_sw", "dividend_yield2"]:
        r = w.wss(code, field, param)
        if r.ErrorCode == 0 and r.Data and r.Data[0] and r.Data[0][0] is not None:
            print(f"  wss {field:30s} ({param}) -> OK: {r.Data[0][0]}")
        else:
            print(f"  wss {field:30s} ({param}) -> FAIL (err={r.ErrorCode})")

# 尝试 wsd 带不同参数
print("\n=== wsd 带参数 ===")
wsd_params = ["priceType=F", "PriceAdj=F"]
for param in wsd_params:
    r = w.wsd(code, "mkt_cap_total", "2024-09-10", "2024-09-10", param)
    if r.ErrorCode == 0 and r.Data and r.Data[0] and r.Data[0][0] is not None:
        print(f"  wsd mkt_cap_total ({param}) -> OK: {r.Data[0][0]}")
    else:
        print(f"  wsd mkt_cap_total ({param}) -> FAIL (err={r.ErrorCode})")

# 尝试 EDP 函数 (wind 数据浏览器)
print("\n=== wss 更多字段探测 ===")
more_fields = [
    "close", "open", "high", "low", "volume", "amount",
    "pct_chg", "pct_chng", "涨跌幅",
    "val_ltm_profit", "net_profit",
    "rev_oper", "rev_profit",
    "bps", "bps_lf",
    "dividend_ratio", "dividendyield",
]
for field in more_fields:
    r = w.wss(code, field)
    if r.ErrorCode == 0 and r.Data and r.Data[0] and r.Data[0][0] is not None:
        print(f"  {field:30s} -> OK: {r.Data[0][0]}")
    else:
        print(f"  {field:30s} -> FAIL")

w.stop()
