#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WindPy 市场基础数据 API 探测脚本
探测股票/期货/理财/企业的可用接口
"""

import os, sys

WIND_X64 = r"D:\Wind\Wind.NET.Client\WindNET\x64"
WIND_BIN = r"D:\Wind\Wind.NET.Client\WindNET\bin"
for p in [WIND_X64, WIND_BIN]:
    if p not in sys.path:
        sys.path.insert(0, p)
os.environ["PATH"] = WIND_X64 + ";" + WIND_BIN + ";" + os.environ.get("PATH", "")

from WindPy import w

def test_stock_api():
    """探测股票数据 API"""
    print("=" * 60)
    print("1. 股票数据 API 探测")
    print("=" * 60)

    # 方案A: wset sectorconstituents 获取全部A股
    print("\n[A] wset sectorconstituents (全部A股)...")
    r = w.wset("sectorconstituents", "sectorid=a001010100000000;field=wind_code,sec_name")
    print(f"  ErrorCode={r.ErrorCode}, 数据量={len(r.Data[0]) if r.Data else 0}")
    if r.ErrorCode == 0 and r.Data and len(r.Data[0]) > 0:
        print(f"  前3条: {list(zip(r.Data[0][:3], r.Data[1][:3]))}")
        return "wset_sector"

    # 方案B: wset 用板块 ID 获取指数成分股
    print("\n[B] wset sectorconstituents (沪深300成分股)...")
    r = w.wset("sectorconstituents", "sectorid=1000003030000000;field=wind_code,sec_name")
    print(f"  ErrorCode={r.ErrorCode}, 数据量={len(r.Data[0]) if r.Data else 0}")
    if r.ErrorCode == 0 and r.Data and len(r.Data[0]) > 0:
        print(f"  前3条: {list(zip(r.Data[0][:3], r.Data[1][:3]))}")
        return "wset_index"

    # 方案C: wss 批量扫描股票代码
    print("\n[C] wss 扫描股票代码 (000001-000010.SZ)...")
    codes = [f"{i:06d}.SZ" for i in range(1, 11)]
    r = w.wss(codes, "sec_name,ipo_date,industry_sw1")
    print(f"  ErrorCode={r.ErrorCode}")
    if r.ErrorCode == 0 and r.Data:
        for i, code in enumerate(codes):
            name = r.Data[0][i] if r.Data[0] else ''
            print(f"    {code}: {name}")
        return "wss_scan"

    print("  所有方案失败!")
    return None


def test_futures_api():
    """探测期货数据 API"""
    print("\n" + "=" * 60)
    print("2. 期货数据 API 探测")
    print("=" * 60)

    # 方案A: wset 获取期货板块
    print("\n[A] wset sectorconstituents (期货)...")
    r = w.wset("sectorconstituents", "sectorid=1000006000000000;field=wind_code,sec_name")
    print(f"  ErrorCode={r.ErrorCode}, 数据量={len(r.Data[0]) if r.Data else 0}")

    # 方案B: wss 直接查已知主力合约
    print("\n[B] wss 查询主力合约...")
    codes = ["RB0.SHF", "CU0.SHF", "IF0.CFX", "T0.CFE", "A0.DCE", "SR0.CZC"]
    r = w.wss(codes, "sec_name,futures_contract_type,futures_delivery_date,futures_listing_date")
    print(f"  ErrorCode={r.ErrorCode}")
    if r.ErrorCode == 0 and r.Data:
        for i, code in enumerate(codes):
            name = r.Data[0][i] if len(r.Data) > 0 and r.Data[0] else ''
            ctype = r.Data[1][i] if len(r.Data) > 1 and r.Data[1] else ''
            print(f"    {code}: {name} | {ctype}")
        return "wss_direct"

    # 方案C: 尝试不同的后缀
    print("\n[C] wss 尝试不同后缀 (.SHF/.DCE/.CZC/.CFE)...")
    test_codes = [
        "RB00.SHF", "CU00.SHF",  # 上期所
        "A00.DCE", "M00.DCE",    # 大商所
        "SR00.CZC", "TA00.CZC",  # 郑商所
        "IF00.CFE", "T00.CFE",   # 中金所
    ]
    r = w.wss(test_codes, "sec_name")
    print(f"  ErrorCode={r.ErrorCode}")
    if r.ErrorCode == 0 and r.Data:
        for i, code in enumerate(test_codes):
            name = r.Data[0][i] if r.Data[0] else ''
            if name and str(name) not in ('', 'None', 'nan'):
                print(f"    {code}: {name}")
        return "wss_alt"

    print("  所有方案失败!")
    return None


def test_wealth_api():
    """探测理财产品 API"""
    print("\n" + "=" * 60)
    print("3. 理财产品 API 探测")
    print("=" * 60)

    # 方案A: wset 板块
    print("\n[A] wset sectorconstituents (银行理财)...")
    r = w.wset("sectorconstituents", "sectorid=1000008100000000;field=wind_code,sec_name")
    print(f"  ErrorCode={r.ErrorCode}, 数据量={len(r.Data[0]) if r.Data else 0}")

    # 方案B: wss 尝试理财代码
    print("\n[B] wss 尝试理财代码格式...")
    # 银行理财代码通常以 Z 开头
    test_codes = ["Z00001", "Z10001", "Z700001"]
    r = w.wss(test_codes, "sec_name")
    print(f"  ErrorCode={r.ErrorCode}")
    if r.ErrorCode == 0 and r.Data:
        for i, code in enumerate(test_codes):
            name = r.Data[0][i] if r.Data[0] else ''
            print(f"    {code}: {name}")

    print("  (理财产品数据可能需通过其他接口获取)")


def test_enterprise_api():
    """探测企业信息 API"""
    print("\n" + "=" * 60)
    print("4. 企业信息 API 探测")
    print("=" * 60)

    # 方案A: wss 查询股票的公司信息
    print("\n[A] wss 查询上市公司信息...")
    codes = ["600519.SH", "000858.SZ", "002594.SZ"]
    r = w.wss(codes, "sec_name,industry_sw1,ipo_date,total_share,total_market_cap,pe_ttm,pb_lf,roe_ttm,dividend_yield2")
    print(f"  ErrorCode={r.ErrorCode}")
    if r.ErrorCode == 0 and r.Data:
        fields = ["sec_name", "industry_sw1", "ipo_date", "total_share", "total_market_cap", "pe_ttm", "pb_lf", "roe_ttm", "dividend_yield2"]
        for i, code in enumerate(codes):
            print(f"  {code}:")
            for j, field in enumerate(fields):
                val = r.Data[j][i] if j < len(r.Data) and i < len(r.Data[j]) else ''
                print(f"    {field:20s} = {val}")
        return "wss_enterprise"

    print("  方案失败!")
    return None


def test_stock_scan():
    """测试股票代码扫描"""
    print("\n" + "=" * 60)
    print("5. 股票代码扫描测试 (前 100 个 SZ 代码)")
    print("=" * 60)

    codes = [f"{i:06d}.SZ" for i in range(1, 101)]
    r = w.wss(codes, "sec_name,ipo_date,industry_sw1")
    print(f"  ErrorCode={r.ErrorCode}")
    if r.ErrorCode == 0 and r.Data:
        valid = 0
        for i, code in enumerate(codes):
            name = r.Data[0][i] if r.Data[0] else ''
            if name and str(name).strip() not in ('', 'None', 'nan'):
                valid += 1
                if valid <= 5:
                    industry = r.Data[2][i] if len(r.Data) > 2 and r.Data[2] else ''
                    print(f"    {code}: {name} | {industry}")
        print(f"  有效股票: {valid}/100")


def main():
    print("=" * 60)
    print("WindPy 市场基础数据 API 探测")
    print("=" * 60)

    start_res = w.start()
    if start_res.ErrorCode != 0:
        print(f"WindPy 启动失败: ErrorCode={start_res.ErrorCode}")
        return

    print("WindPy 启动成功\n")

    test_stock_api()
    test_futures_api()
    test_wealth_api()
    test_enterprise_api()
    test_stock_scan()

    w.stop()
    print("\n探测完成!")


if __name__ == "__main__":
    main()
