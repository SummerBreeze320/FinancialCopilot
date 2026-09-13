#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WindPy 市场基础数据拉取 (股票/期货/理财/上市企业)
=====================================================
扫描 A 股代码获取全量股票基础信息,
拉取期货主力合约, 生成理财产品样本数据。
"""

import os, sys, json, datetime, hashlib
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
WSS_CHUNK = 50
SCAN_CHUNK = 100

OUTPUT_DIR = Path(__file__).parent

# A 股代码范围 (start, end, exchange, suffix)
STOCK_RANGES = [
    (600000, 604000, "SSE", "SH"),
    (605000, 606000, "SSE", "SH"),
    (688000, 690000, "SSE", "SH"),
    (1, 3000, "SZSE", "SZ"),
    (300000, 301000, "SZSE", "SZ"),
]

# 期货主力合约列表 (code, exchange, category, underlying)
FUTURES_CONTRACTS = [
    # 上期所 (SHFE)
    ("CU00.SHF", "SHFE", "商品期货", "铜"),
    ("AL00.SHF", "SHFE", "商品期货", "铝"),
    ("ZN00.SHF", "SHFE", "商品期货", "锌"),
    ("PB00.SHF", "SHFE", "商品期货", "铅"),
    ("NI00.SHF", "SHFE", "商品期货", "镍"),
    ("SN00.SHF", "SHFE", "商品期货", "锡"),
    ("AU00.SHF", "SHFE", "商品期货", "黄金"),
    ("AG00.SHF", "SHFE", "商品期货", "白银"),
    ("RB00.SHF", "SHFE", "商品期货", "螺纹钢"),
    ("HC00.SHF", "SHFE", "商品期货", "热轧卷板"),
    ("WR00.SHF", "SHFE", "商品期货", "线材"),
    ("FU00.SHF", "SHFE", "商品期货", "燃料油"),
    ("BU00.SHF", "SHFE", "商品期货", "沥青"),
    ("RU00.SHF", "SHFE", "商品期货", "橡胶"),
    ("SP00.SHF", "SHFE", "商品期货", "纸浆"),
    ("SS00.SHF", "SHFE", "商品期货", "不锈钢"),
    ("BC00.SHF", "SHFE", "商品期货", "国际铜"),
    ("LU00.SHF", "SHFE", "商品期货", "低硫燃料油"),
    ("NR00.SHF", "SHFE", "商品期货", "20号胶"),
    ("AO00.SHF", "SHFE", "商品期货", "氧化铝"),
    # 大商所 (DCE)
    ("A00.DCE",  "DCE",  "商品期货", "豆一"),
    ("B00.DCE",  "DCE",  "商品期货", "豆二"),
    ("M00.DCE",  "DCE",  "商品期货", "豆粕"),
    ("Y00.DCE",  "DCE",  "商品期货", "豆油"),
    ("P00.DCE",  "DCE",  "商品期货", "棕榈油"),
    ("C00.DCE",  "DCE",  "商品期货", "玉米"),
    ("CS00.DCE", "DCE",  "商品期货", "玉米淀粉"),
    ("JD00.DCE", "DCE",  "商品期货", "鸡蛋"),
    ("L00.DCE",  "DCE",  "商品期货", "聚乙烯"),
    ("V00.DCE",  "DCE",  "商品期货", "聚氯乙烯"),
    ("PP00.DCE", "DCE",  "商品期货", "聚丙烯"),
    ("J00.DCE",  "DCE",  "商品期货", "焦炭"),
    ("JM00.DCE", "DCE",  "商品期货", "焦煤"),
    ("I00.DCE",  "DCE",  "商品期货", "铁矿石"),
    ("EG00.DCE", "DCE",  "商品期货", "乙二醇"),
    ("EB00.DCE", "DCE",  "商品期货", "苯乙烯"),
    ("PG00.DCE", "DCE",  "商品期货", "液化石油气"),
    ("RR00.DCE", "DCE",  "商品期货", "粳米"),
    ("FB00.DCE", "DCE",  "商品期货", "纤维板"),
    ("BB00.DCE", "DCE",  "商品期货", "胶合板"),
    # 郑商所 (CZCE)
    ("WH00.CZC", "CZCE", "商品期货", "强麦"),
    ("PM00.CZC", "CZCE", "商品期货", "普麦"),
    ("CT00.CZC", "CZCE", "商品期货", "棉花"),
    ("SR00.CZC", "CZCE", "商品期货", "白糖"),
    ("TA00.CZC", "CZCE", "商品期货", "PTA"),
    ("OI00.CZC", "CZCE", "商品期货", "菜油"),
    ("RI00.CZC", "CZCE", "商品期货", "早籼稻"),
    ("MA00.CZC", "CZCE", "商品期货", "甲醇"),
    ("FG00.CZC", "CZCE", "商品期货", "玻璃"),
    ("RS00.CZC", "CZCE", "商品期货", "菜籽"),
    ("RM00.CZC", "CZCE", "商品期货", "菜粕"),
    ("ZC00.CZC", "CZCE", "商品期货", "动力煤"),
    ("SF00.CZC", "CZCE", "商品期货", "硅铁"),
    ("SM00.CZC", "CZCE", "商品期货", "锰硅"),
    ("AP00.CZC", "CZCE", "商品期货", "苹果"),
    ("CJ00.CZC", "CZCE", "商品期货", "红枣"),
    ("UR00.CZC", "CZCE", "商品期货", "尿素"),
    ("SA00.CZC", "CZCE", "商品期货", "纯碱"),
    ("PF00.CZC", "CZCE", "商品期货", "短纤"),
    ("PK00.CZC", "CZCE", "商品期货", "花生"),
    ("SH00.CZC", "CZCE", "商品期货", "烧碱"),
    ("PX00.CZC", "CZCE", "商品期货", "对二甲苯"),
    # 中金所 (CFFEX)
    ("IF00.CFE", "CFFEX", "金融期货", "沪深300"),
    ("IH00.CFE", "CFFEX", "金融期货", "上证50"),
    ("IC00.CFE", "CFFEX", "金融期货", "中证500"),
    ("IM00.CFE", "CFFEX", "金融期货", "中证1000"),
    ("T00.CFE",  "CFFEX", "金融期货", "10年期国债"),
    ("TF00.CFE", "CFFEX", "金融期货", "5年期国债"),
    ("TS00.CFE", "CFFEX", "金融期货", "2年期国债"),
    ("TL00.CFE", "CFFEX", "金融期货", "30年期国债"),
    # 广期所 (GFEX)
    ("SI00.GFE", "GFEX", "商品期货", "工业硅"),
    ("LC00.GFE", "GFEX", "商品期货", "碳酸锂"),
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

def chunk_list(lst, size):
    for i in range(0, len(lst), size):
        yield lst[i:i + size]

# ── 1. 扫描 A 股代码 ────────────────────────────────────────────
def scan_stock_codes(w):
    """扫描 A 股代码, 返回有效股票列表 [{code, name, exchange, suffix}]"""
    print("[1/6] 扫描 A 股代码...", flush=True)

    all_valid = []
    total_scanned = 0

    for start, end, exchange, suffix in STOCK_RANGES:
        for batch_start in range(start, end, SCAN_CHUNK):
            batch_end = min(batch_start + SCAN_CHUNK, end)
            codes = [f"{i:06d}.{suffix}" for i in range(batch_start, batch_end)]
            r = w.wss(codes, "sec_name")

            if r.ErrorCode != 0:
                continue

            for i, code in enumerate(codes):
                name = safe_str(r.Data[0][i]) if r.Data and len(r.Data) > 0 else ''
                if name and name not in ('None', 'nan'):
                    all_valid.append({
                        "code": code,
                        "name": name,
                        "exchange": exchange,
                        "suffix": suffix,
                    })

            total_scanned += len(codes)

        count_str = f"{start:06d}-{end:06d}.{suffix}"
        print(f"  {count_str}: 累计有效 {len(all_valid):5d} (扫描 {total_scanned})", flush=True)

    print(f"  扫描完成: {total_scanned} 个代码, {len(all_valid)} 只有效股票", flush=True)
    return all_valid

# ── 2. 拉取股票详细信息 ─────────────────────────────────────────
def fetch_stock_details(w, stocks):
    """批量拉取股票详细信息"""
    print(f"\n[2/6] 拉取股票详细信息 ({len(stocks)} 只)...", flush=True)

    FIELDS = "sec_name,ipo_date,pe_ttm,pb_lf,roe_ttm,total_shares,close"

    for chunk in chunk_list(stocks, WSS_CHUNK):
        codes = [s["code"] for s in chunk]
        r = w.wss(codes, FIELDS)
        if r.ErrorCode != 0:
            print(f"  wss 错误: {r.ErrorCode}", flush=True)
            continue

        for i, s in enumerate(chunk):
            s["ipo_date"] = safe_date(r.Data[1][i]) if len(r.Data) > 1 else '2020-01-01'
            s["pe_ttm"] = safe_float(r.Data[2][i]) if len(r.Data) > 2 else 0.0
            s["pb"] = safe_float(r.Data[3][i]) if len(r.Data) > 3 else 0.0
            s["roe"] = safe_float(r.Data[4][i]) if len(r.Data) > 4 else 0.0
            s["total_shares"] = safe_float(r.Data[5][i]) if len(r.Data) > 5 else 0.0
            s["close"] = safe_float(r.Data[6][i]) if len(r.Data) > 6 else 0.0
            s["market_cap_billion"] = round(s["close"] * s["total_shares"] / 1e8, 2)

        idx = stocks.index(chunk[-1])
        if (idx + 1) % 500 == 0 or idx + 1 == len(stocks):
            print(f"  进度: {idx + 1}/{len(stocks)}", flush=True)

    # 从基金持仓数据获取行业信息
    fund_data_file = OUTPUT_DIR / "wind_demo_data.json"
    if fund_data_file.exists():
        with open(fund_data_file, encoding='utf-8') as f:
            fund_data = json.load(f)
        stock_industry = {}
        for h in fund_data.get("holdings", []):
            sc = h.get("stock_code", "")
            if sc and h.get("holding_sector"):
                stock_industry[sc] = h["holding_sector"]
        matched = 0
        for s in stocks:
            industry = stock_industry.get(s["code"], "")
            if not industry:
                short_code = s["code"].split(".")[0]
                industry = stock_industry.get(short_code, "")
            s["industry"] = industry or "未分类"
            if industry:
                matched += 1
        print(f"  行业匹配: {matched}/{len(stocks)} (来自基金持仓数据)", flush=True)
    else:
        for s in stocks:
            s["industry"] = "未分类"

    return stocks

# ── 3. 拉取期货合约信息 ─────────────────────────────────────────
def fetch_futures(w):
    print(f"\n[3/6] 拉取期货合约信息 ({len(FUTURES_CONTRACTS)} 个)...", flush=True)

    futures = []
    codes = [c[0] for c in FUTURES_CONTRACTS]

    for chunk in chunk_list(codes, WSS_CHUNK):
        r = w.wss(chunk, "sec_name")
        if r.ErrorCode != 0:
            continue
        for i, code in enumerate(chunk):
            name = safe_str(r.Data[0][i]) if r.Data and len(r.Data) > 0 else ''
            if not name:
                continue
            meta = next((c for c in FUTURES_CONTRACTS if c[0] == code), None)
            futures.append({
                "code": code,
                "name": name,
                "exchange": meta[1] if meta else "",
                "category": meta[2] if meta else "商品期货",
                "underlying": meta[3] if meta else "",
            })

    print(f"  获取 {len(futures)} 个期货合约", flush=True)
    return futures

# ── 4. 生成理财产品数据 ─────────────────────────────────────────
def generate_wealth_products():
    """生成代表性银行理财产品数据"""
    print("\n[4/6] 生成理财产品样本数据...", flush=True)

    products = [
        ("WM0001", "招商银行朝朝宝", "招商银行", "固定收益类", "R1", 2.80, 1.00, 0, "2024-01-01", "2025-12-31"),
        ("WM0002", "招商银行朝朝金", "招商银行", "固定收益类", "R2", 3.20, 1000.00, 30, "2024-03-01", "2025-03-01"),
        ("WM0003", "工银理财鑫得利固收", "工商银行", "固定收益类", "R2", 3.50, 1000.00, 90, "2024-01-15", "2024-04-15"),
        ("WM0004", "建行乾元优享固收", "建设银行", "固定收益类", "R2", 3.80, 5000.00, 180, "2024-02-01", "2024-08-01"),
        ("WM0005", "中银理财智富固收增强", "中国银行", "固定收益类", "R3", 4.20, 10000.00, 365, "2024-01-10", "2025-01-10"),
        ("WM0006", "农行安心快利", "农业银行", "固定收益类", "R1", 2.90, 100.00, 7, "2024-06-01", "2024-06-08"),
        ("WM0007", "交行沃德金享", "交通银行", "混合类", "R3", 4.50, 10000.00, 365, "2024-03-01", "2025-03-01"),
        ("WM0008", "邮储理财财富债券", "邮储银行", "固定收益类", "R2", 3.60, 1000.00, 180, "2024-02-15", "2024-08-15"),
        ("WM0009", "兴业银行万利宝", "兴业银行", "混合类", "R3", 4.80, 5000.00, 365, "2024-01-20", "2025-01-20"),
        ("WM0010", "浦发银行同享盈", "浦发银行", "混合类", "R3", 4.30, 1000.00, 180, "2024-04-01", "2024-10-01"),
        ("WM0011", "民生银行非凡资产管理", "民生银行", "混合类", "R3", 4.10, 1000.00, 365, "2024-02-01", "2025-02-01"),
        ("WM0012", "中信银行共赢稳健", "中信银行", "固定收益类", "R2", 3.70, 1000.00, 90, "2024-05-01", "2024-08-01"),
        ("WM0013", "光大银行阳光金日盈", "光大银行", "固定收益类", "R1", 2.70, 1.00, 0, "2024-01-01", "2025-12-31"),
        ("WM0014", "平安银行稳盈系列", "平安银行", "固定收益类", "R2", 3.40, 1000.00, 180, "2024-03-15", "2024-09-15"),
        ("WM0015", "华夏银行龙盈理财", "华夏银行", "混合类", "R3", 4.20, 5000.00, 365, "2024-02-10", "2025-02-10"),
        ("WM0016", "北京银行京华远图", "北京银行", "固定收益类", "R2", 3.30, 1000.00, 90, "2024-04-01", "2024-07-01"),
        ("WM0017", "宁波银行汇通理财", "宁波银行", "混合类", "R3", 4.50, 10000.00, 365, "2024-01-25", "2025-01-25"),
        ("WM0018", "南京银行鑫欢喜", "南京银行", "固定收益类", "R2", 3.60, 1000.00, 180, "2024-03-01", "2024-09-01"),
        ("WM0019", "杭州银行幸福理财", "杭州银行", "混合类", "R3", 4.10, 5000.00, 365, "2024-02-05", "2025-02-05"),
        ("WM0020", "江苏银行聚宝理财", "江苏银行", "固定收益类", "R2", 3.50, 1000.00, 90, "2024-05-15", "2024-08-15"),
    ]

    wealth = []
    for p in products:
        wealth.append({
            "product_code": p[0],
            "product_name": p[1],
            "bank_name": p[2],
            "product_type": p[3],
            "risk_level": p[4],
            "expected_annual_return": p[5],
            "min_purchase_amount": p[6],
            "product_term_days": p[7],
            "start_date": p[8],
            "end_date": p[9],
        })

    print(f"  生成 {len(wealth)} 个理财产品", flush=True)
    return wealth

# ── 5. 构建上市企业信息 ──────────────────────────────────────────
def build_enterprises(stocks):
    """从股票数据构建上市企业信息"""
    print(f"\n[5/6] 构建上市企业信息 ({len(stocks)} 家)...", flush=True)

    enterprises = []
    for s in stocks:
        enterprises.append({
            "enterprise_id": s["code"].split(".")[0],
            "enterprise_name": s["name"],
            "stock_code": s["code"],
            "exchange": s["exchange"],
            "industry": s.get("industry", "未分类"),
            "listing_date": s.get("ipo_date", "2020-01-01"),
            "total_shares": round(s.get("total_shares", 0) / 1e4, 2),  # 万股
            "market_cap_billion": s.get("market_cap_billion", 0.0),
            "legal_representative": "",
            "registered_capital": 0.0,
            "business_scope": "",
        })

    return enterprises

# ── 6. 生成输出文件 ──────────────────────────────────────────────
def generate_output(stocks, futures, wealth, enterprises):
    print("\n[6/6] 生成输出文件...", flush=True)

    now = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')

    data = {
        "meta": {
            "generated_at": now,
            "source": "WindPy (Wind 金融终端)",
            "stock_count": len(stocks),
            "futures_count": len(futures),
            "wealth_count": len(wealth),
            "enterprise_count": len(enterprises),
        },
        "stocks": stocks,
        "futures": futures,
        "wealth_products": wealth,
        "enterprises": enterprises,
    }

    # JSON
    json_path = OUTPUT_DIR / "market_basics_data.json"
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print(f"  JSON: {json_path} ({json_path.stat().st_size / 1024:.0f} KB)", flush=True)

    # SQL
    sql_path = OUTPUT_DIR / "market_basics_dump.sql"
    with open(sql_path, "w", encoding="utf-8") as f:
        f.write("-- 市场基础数据 SQL Dump\n")
        f.write(f"-- 生成时间: {now}\n\n")

        f.write("-- 清空旧数据\n")
        f.write("TRUNCATE stock_info RESTART IDENTITY CASCADE;\n")
        f.write("TRUNCATE futures_info RESTART IDENTITY CASCADE;\n")
        f.write("TRUNCATE wealth_product_info RESTART IDENTITY CASCADE;\n")
        f.write("TRUNCATE enterprise_info RESTART IDENTITY CASCADE;\n\n")

        # stock_info
        f.write("-- stock_info\n")
        for s in stocks:
            f.write(
                f"INSERT INTO stock_info (stock_code, stock_name, exchange, industry, "
                f"pe_ttm, pb, market_cap_billion, roe, listing_date, total_shares, close_price) "
                f"VALUES ('{escape_sql(s['code'])}', '{escape_sql(s['name'])}', '{s['exchange']}', "
                f"'{escape_sql(s.get('industry', '未分类'))}', {s.get('pe_ttm', 0)}, {s.get('pb', 0)}, "
                f"{s.get('market_cap_billion', 0)}, {s.get('roe', 0)}, "
                f"'{s.get('ipo_date', '2020-01-01')}', {s.get('total_shares', 0)}, {s.get('close', 0)}) "
                f"ON CONFLICT (stock_code) DO NOTHING;\n"
            )

        # futures_info
        f.write("\n-- futures_info\n")
        for fu in futures:
            f.write(
                f"INSERT INTO futures_info (futures_code, futures_name, exchange, category, underlying) "
                f"VALUES ('{escape_sql(fu['code'])}', '{escape_sql(fu['name'])}', '{fu['exchange']}', "
                f"'{fu['category']}', '{escape_sql(fu['underlying'])}') "
                f"ON CONFLICT (futures_code) DO NOTHING;\n"
            )

        # wealth_product_info
        f.write("\n-- wealth_product_info\n")
        for wp in wealth:
            f.write(
                f"INSERT INTO wealth_product_info (product_code, product_name, bank_name, product_type, "
                f"risk_level, expected_annual_return, min_purchase_amount, product_term_days, "
                f"start_date, end_date) "
                f"VALUES ('{wp['product_code']}', '{escape_sql(wp['product_name'])}', "
                f"'{escape_sql(wp['bank_name'])}', '{wp['product_type']}', '{wp['risk_level']}', "
                f"{wp['expected_annual_return']}, {wp['min_purchase_amount']}, {wp['product_term_days']}, "
                f"'{wp['start_date']}', '{wp['end_date']}') "
                f"ON CONFLICT (product_code) DO NOTHING;\n"
            )

        # enterprise_info
        f.write("\n-- enterprise_info\n")
        for e in enterprises:
            f.write(
                f"INSERT INTO enterprise_info (enterprise_id, enterprise_name, stock_code, exchange, "
                f"industry, listing_date, total_shares, market_cap_billion, legal_representative, "
                f"registered_capital, business_scope) "
                f"VALUES ('{escape_sql(e['enterprise_id'])}', '{escape_sql(e['enterprise_name'])}', "
                f"'{escape_sql(e['stock_code'])}', '{e['exchange']}', '{escape_sql(e['industry'])}', "
                f"'{e['listing_date']}', {e['total_shares']}, {e['market_cap_billion']}, "
                f"'{escape_sql(e.get('legal_representative', ''))}', {e.get('registered_capital', 0)}, "
                f"'{escape_sql(e.get('business_scope', ''))}') "
                f"ON CONFLICT (enterprise_id) DO NOTHING;\n"
            )

    print(f"  SQL: {sql_path} ({sql_path.stat().st_size / 1024:.0f} KB)", flush=True)

# ── 主流程 ──────────────────────────────────────────────────────
def main():
    from WindPy import w

    print("=" * 60)
    print("WindPy 市场基础数据拉取 (股票/期货/理财/企业)")
    print("=" * 60)

    # 启动 WindPy
    print("\n启动 WindPy...", flush=True)
    start_res = w.start()
    if start_res.ErrorCode != 0:
        print(f"WindPy 启动失败: {start_res.ErrorCode}")
        return

    # 1. 扫描股票代码
    stocks = scan_stock_codes(w)

    # 2. 拉取股票详细信息
    fetch_stock_details(w, stocks)

    # 3. 拉取期货合约
    futures = fetch_futures(w)

    # 4. 生成理财产品
    wealth = generate_wealth_products()

    # 5. 构建企业信息
    enterprises = build_enterprises(stocks)

    # 6. 生成输出
    generate_output(stocks, futures, wealth, enterprises)

    w.stop()
    print("\n" + "=" * 60)
    print(f"完成! 股票 {len(stocks)} | 期货 {len(futures)} | 理财 {len(wealth)} | 企业 {len(enterprises)}")
    print("=" * 60)

if __name__ == "__main__":
    main()
