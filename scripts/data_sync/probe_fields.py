#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""股票字段名探测"""

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

# 批量探测字段名
fields_to_test = [
    # 行业相关
    "industry", "industry_sw", "industry_sw1", "sw_industry",
    "industry_zx", "industry_cs", "sec_industry",
    # 市值相关
    "mkt_cap_total", "mktcap", "val_mktcap", "market_cap",
    "mkt_cap_float", "mkt_cap_ar",
    # 股本相关
    "total_shares", "share_total", "total_share", "val_cap",
    # 基本面
    "roe_ttm", "dividend_yield2", "roe_basic",
    # 交易所
    "exchange", "exch", "sec_type",
    # 营收利润
    "rev_gr", "net_profit_gr", "oper_rev",
]

for field in fields_to_test:
    r = w.wss(code, field)
    if r.ErrorCode == 0 and r.Data and r.Data[0] and r.Data[0][0] is not None:
        val = r.Data[0][0]
        print(f"  {field:30s} -> OK: {val}")
    else:
        print(f"  {field:30s} -> FAIL (err={r.ErrorCode})")

w.stop()
