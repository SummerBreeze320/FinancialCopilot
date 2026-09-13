#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""股票 API 深入探测"""

import os, sys

WIND_X64 = r"D:\Wind\Wind.NET.Client\WindNET\x64"
WIND_BIN = r"D:\Wind\Wind.NET.Client\WindNET\bin"
for p in [WIND_X64, WIND_BIN]:
    if p not in sys.path:
        sys.path.insert(0, p)
os.environ["PATH"] = WIND_X64 + ";" + WIND_BIN + ";" + os.environ.get("PATH", "")

from WindPy import w

w.start()
print("WindPy started\n")

# 测试1: 单只股票不同后缀
print("=== 测试1: 单只股票不同后缀 ===")
for suffix in ['.SH', '.SZ', '.SHSE', '.SZSE', '.SHE']:
    code = f"600519{suffix}"
    r = w.wss(code, "sec_name")
    print(f"  {code}: ErrorCode={r.ErrorCode}, Data={r.Data}")

# 测试2: 不同字段名
print("\n=== 测试2: 不同字段名 (600519.SH) ===")
field_tests = [
    "sec_name",
    "sec_name,trade_status",
    "sec_name,listdate",
    "sec_name,ipo_date",
    "sec_name,industry_sw1",
    "sec_name,industry_sw",
    "sec_name,sw_industry",
    "sec_name,total_market_cap",
    "sec_name,market_cap",
    "sec_name,pe_ttm",
    "sec_name,pb_lf",
]
for fields in field_tests:
    r = w.wss("600519.SH", fields)
    status = f"OK: {r.Data}" if r.ErrorCode == 0 else f"ERR={r.ErrorCode}"
    print(f"  {fields:40s} -> {status}")

# 测试3: wsd 时序数据
print("\n=== 测试3: wsd 时序数据 (600519.SH) ===")
r = w.wsd("600519.SH", "sec_name", "2024-09-10", "2024-09-10", "")
print(f"  wsd sec_name: ErrorCode={r.ErrorCode}, Data={r.Data}")

r = w.wsd("600519.SH", "close", "2024-09-10", "2024-09-10", "")
print(f"  wsd close: ErrorCode={r.ErrorCode}, Data={r.Data}")

# 测试4: wss 多只股票
print("\n=== 测试4: wss 多只股票 ===")
codes = ["600519.SH", "000858.SZ", "002594.SZ"]
r = w.wss(codes, "sec_name")
print(f"  ErrorCode={r.ErrorCode}, Data={r.Data}")

# 测试5: 不带后缀
print("\n=== 测试5: 不带后缀 ===")
for code in ["600519", "000858", "002594"]:
    r = w.wss(code, "sec_name")
    print(f"  {code}: ErrorCode={r.ErrorCode}, Data={r.Data}")

# 测试6: 尝试 wset 不同参数
print("\n=== 测试6: wset 板块成分 (不同 sectorid) ===")
sector_ids = [
    "a001010100000000",  # 全部A股
    "1000003010000000",  # 上证
    "1000004010000000",  # 深证
    "1000003030000000",  # 沪深300
]
for sid in sector_ids:
    r = w.wset("sectorconstituents", f"sectorid={sid}")
    cnt = len(r.Data[0]) if r.ErrorCode == 0 and r.Data else 0
    print(f"  sectorid={sid}: ErrorCode={r.ErrorCode}, count={cnt}")

# 测试7: 尝试 wset 不同函数名
print("\n=== 测试7: wset 不同函数名 ===")
for func in ["sectorconstituents", "stockconstrast", "allstock", "stockbasic"]:
    r = w.wset(func, "")
    print(f"  {func}: ErrorCode={r.ErrorCode}")

w.stop()
print("\nDone!")
