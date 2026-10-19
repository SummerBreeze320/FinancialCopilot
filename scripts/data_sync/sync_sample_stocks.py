#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
A股代表性标的样本数据生成脚本
涵盖股票基础信息、日K线时序、季度财务指标、分红历史、十大股东与研报定性文本。
无需网络连接，直接生成 SQL dump 文件。
"""

import os
import datetime
from decimal import Decimal

OUTPUT_DIR = os.path.dirname(os.path.abspath(__file__))

# ── 10 只核心 A 股标的 ──────────────────────────────────────────────
SAMPLE_STOCKS = [
    {"stock_code": "600519.SH", "stock_name": "贵州茅台", "exchange": "SSE", "industry": "食品饮料",
     "pe_ttm": 28.50, "pb": 8.90, "roe": 32.50, "dividend_yield": 1.80, "market_cap_billion": 21000.0,
     "listing_date": "2001-08-27", "total_shares": 125619.78, "close_price": 1672.00},
    {"stock_code": "000858.SZ", "stock_name": "五粮液", "exchange": "SZSE", "industry": "食品饮料",
     "pe_ttm": 20.30, "pb": 5.60, "roe": 26.80, "dividend_yield": 2.50, "market_cap_billion": 5200.0,
     "listing_date": "1998-04-27", "total_shares": 38816.08, "close_price": 134.00},
    {"stock_code": "002415.SZ", "stock_name": "海康威视", "exchange": "SZSE", "industry": "电子/安防",
     "pe_ttm": 18.50, "pb": 3.80, "roe": 20.50, "dividend_yield": 2.80, "market_cap_billion": 3100.0,
     "listing_date": "2010-05-28", "total_shares": 932828.13, "close_price": 33.20},
    {"stock_code": "000333.SZ", "stock_name": "美的集团", "exchange": "SZSE", "industry": "家用电器",
     "pe_ttm": 12.80, "pb": 3.20, "roe": 25.00, "dividend_yield": 3.50, "market_cap_billion": 4500.0,
     "listing_date": "2013-09-18", "total_shares": 705896.42, "close_price": 63.80},
    {"stock_code": "300750.SZ", "stock_name": "宁德时代", "exchange": "SZSE", "industry": "电力设备",
     "pe_ttm": 22.00, "pb": 5.10, "roe": 22.00, "dividend_yield": 0.50, "market_cap_billion": 8500.0,
     "listing_date": "2018-06-11", "total_shares": 440339.54, "close_price": 193.10},
    {"stock_code": "600276.SH", "stock_name": "恒瑞医药", "exchange": "SSE", "industry": "医药生物",
     "pe_ttm": 45.00, "pb": 8.20, "roe": 18.50, "dividend_yield": 0.60, "market_cap_billion": 3200.0,
     "listing_date": "2000-10-18", "total_shares": 638668.48, "close_price": 50.10},
    {"stock_code": "603259.SH", "stock_name": "药明康德", "exchange": "SSE", "industry": "医药生物/CXO",
     "pe_ttm": 15.00, "pb": 3.00, "roe": 19.50, "dividend_yield": 1.20, "market_cap_billion": 1500.0,
     "listing_date": "2018-05-08", "total_shares": 297697.36, "close_price": 50.40},
    {"stock_code": "300760.SZ", "stock_name": "迈瑞医疗", "exchange": "SZSE", "industry": "医药生物/医疗器械",
     "pe_ttm": 25.00, "pb": 7.50, "roe": 30.00, "dividend_yield": 1.00, "market_cap_billion": 3600.0,
     "listing_date": "2018-10-16", "total_shares": 121569.31, "close_price": 296.20},
    {"stock_code": "000661.SZ", "stock_name": "长春高新", "exchange": "SZSE", "industry": "医药生物/生物制品",
     "pe_ttm": 12.00, "pb": 2.80, "roe": 23.00, "dividend_yield": 1.50, "market_cap_billion": 560.0,
     "listing_date": "1996-12-18", "total_shares": 40594.68, "close_price": 138.00},
    {"stock_code": "000001.SZ", "stock_name": "平安银行", "exchange": "SZSE", "industry": "银行",
     "pe_ttm": 5.50, "pb": 0.55, "roe": 10.50, "dividend_yield": 5.80, "market_cap_billion": 2200.0,
     "listing_date": "1991-04-03", "total_shares": 1940591.82, "close_price": 11.34},
]

# ── 季度核心财务指标样本 ──────────────────────────────────────────────
SAMPLE_FINANCIALS = [
    {"stock_code": "600519.SH", "report_quarter": "2024Q2", "revenue_billion": 834.51, "revenue_yoy": 17.56,
     "net_profit_billion": 416.96, "net_profit_yoy": 15.88, "eps": 33.19, "gross_margin": 91.80,
     "net_margin": 49.96, "roe": 18.50, "total_assets_billion": 2792.07, "net_assets_billion": 1920.34,
     "debt_to_asset_ratio": 18.20, "operating_cashflow_billion": 388.10, "bps": 152.88},
    {"stock_code": "600519.SH", "report_quarter": "2024Q1", "revenue_billion": 464.85, "revenue_yoy": 18.11,
     "net_profit_billion": 240.65, "net_profit_yoy": 15.73, "eps": 19.16, "gross_margin": 92.10,
     "net_margin": 51.77, "roe": 10.20, "total_assets_billion": 2760.50, "net_assets_billion": 1895.20,
     "debt_to_asset_ratio": 17.80, "operating_cashflow_billion": 91.53, "bps": 150.89},
    {"stock_code": "000858.SZ", "report_quarter": "2024Q2", "revenue_billion": 171.28, "revenue_yoy": 2.95,
     "net_profit_billion": 68.02, "net_profit_yoy": 5.10, "eps": 1.75, "gross_margin": 76.50,
     "net_margin": 39.71, "roe": 13.10, "total_assets_billion": 1006.35, "net_assets_billion": 608.20,
     "debt_to_asset_ratio": 25.60, "operating_cashflow_billion": 45.80, "bps": 15.67},
    {"stock_code": "002415.SZ", "report_quarter": "2024Q2", "revenue_billion": 412.05, "revenue_yoy": 10.25,
     "net_profit_billion": 53.22, "net_profit_yoy": -5.80, "eps": 0.36, "gross_margin": 44.20,
     "net_margin": 12.92, "roe": 5.80, "total_assets_billion": 1230.50, "net_assets_billion": 815.30,
     "debt_to_asset_ratio": 33.10, "operating_cashflow_billion": 28.50, "bps": 8.74},
    {"stock_code": "300750.SZ", "report_quarter": "2024Q2", "revenue_billion": 869.88, "revenue_yoy": -0.58,
     "net_profit_billion": 108.02, "net_profit_yoy": -15.20, "eps": 2.45, "gross_margin": 22.10,
     "net_margin": 12.42, "roe": 8.50, "total_assets_billion": 5680.20, "net_assets_billion": 2350.80,
     "debt_to_asset_ratio": 65.80, "operating_cashflow_billion": 158.30, "bps": 53.39},
    {"stock_code": "600276.SH", "report_quarter": "2024Q2", "revenue_billion": 136.01, "revenue_yoy": 21.70,
     "net_profit_billion": 34.32, "net_profit_yoy": 18.90, "eps": 0.51, "gross_margin": 85.60,
     "net_margin": 25.23, "roe": 7.20, "total_assets_billion": 450.20, "net_assets_billion": 310.50,
     "debt_to_asset_ratio": 12.50, "operating_cashflow_billion": 25.80, "bps": 6.12},
]

# ── 分红送转历史样本 ──────────────────────────────────────────────────
SAMPLE_DIVIDENDS = [
    {"stock_code": "600519.SH", "dividend_year": "2023", "cash_dividend_per_10": 259.11, "ex_dividend_date": "2024-06-28",
     "bonus_shares_per_10": 0, "transfer_shares_per_10": 0, "dividend_ratio": 1.55, "payout_ratio": 51.90},
    {"stock_code": "600519.SH", "dividend_year": "2022", "cash_dividend_per_10": 219.10, "ex_dividend_date": "2023-06-30",
     "bonus_shares_per_10": 0, "transfer_shares_per_10": 0, "dividend_ratio": 1.30, "payout_ratio": 51.60},
    {"stock_code": "000858.SZ", "dividend_year": "2023", "cash_dividend_per_10": 9.68, "ex_dividend_date": "2024-07-05",
     "bonus_shares_per_10": 0, "transfer_shares_per_10": 0, "dividend_ratio": 2.40, "payout_ratio": 55.00},
    {"stock_code": "002415.SZ", "dividend_year": "2023", "cash_dividend_per_10": 1.50, "ex_dividend_date": "2024-07-12",
     "bonus_shares_per_10": 0, "transfer_shares_per_10": 0, "dividend_ratio": 2.80, "payout_ratio": 45.00},
    {"stock_code": "000333.SZ", "dividend_year": "2023", "cash_dividend_per_10": 25.00, "ex_dividend_date": "2024-07-10",
     "bonus_shares_per_10": 0, "transfer_shares_per_10": 0, "dividend_ratio": 3.50, "payout_ratio": 58.00},
    {"stock_code": "300750.SZ", "dividend_year": "2023", "cash_dividend_per_10": 5.08, "ex_dividend_date": "2024-06-20",
     "bonus_shares_per_10": 0, "transfer_shares_per_10": 0, "dividend_ratio": 0.50, "payout_ratio": 20.00},
    {"stock_code": "600276.SH", "dividend_year": "2023", "cash_dividend_per_10": 2.50, "ex_dividend_date": "2024-06-15",
     "bonus_shares_per_10": 0, "transfer_shares_per_10": 0, "dividend_ratio": 0.60, "payout_ratio": 30.00},
    {"stock_code": "000001.SZ", "dividend_year": "2023", "cash_dividend_per_10": 7.19, "ex_dividend_date": "2024-06-25",
     "bonus_shares_per_10": 0, "transfer_shares_per_10": 0, "dividend_ratio": 5.80, "payout_ratio": 30.50},
]

# ── 十大股东样本 ──────────────────────────────────────────────────────
SAMPLE_SHAREHOLDERS = [
    {"stock_code": "600519.SH", "report_quarter": "2024Q2", "rank_order": 1,
     "shareholder_name": "中国贵州茅台酒厂(集团)有限责任公司", "shareholder_type": "机构",
     "holding_shares_ten_thousand": 67939.19, "holding_ratio": 54.08, "holding_change": "不变"},
    {"stock_code": "600519.SH", "report_quarter": "2024Q2", "rank_order": 2,
     "shareholder_name": "贵州省国有资本运营有限责任公司", "shareholder_type": "机构",
     "holding_shares_ten_thousand": 3200.00, "holding_ratio": 2.55, "holding_change": "减持"},
    {"stock_code": "600519.SH", "report_quarter": "2024Q2", "rank_order": 3,
     "shareholder_name": "香港中央结算有限公司(沪股通)", "shareholder_type": "机构",
     "holding_shares_ten_thousand": 2895.42, "holding_ratio": 2.30, "holding_change": "增持"},
    {"stock_code": "000858.SZ", "report_quarter": "2024Q2", "rank_order": 1,
     "shareholder_name": "宜宾五粮液集团有限公司", "shareholder_type": "机构",
     "holding_shares_ten_thousand": 19560.00, "holding_ratio": 50.39, "holding_change": "不变"},
    {"stock_code": "000858.SZ", "report_quarter": "2024Q2", "rank_order": 2,
     "shareholder_name": "宜宾市发展控股集团有限公司", "shareholder_type": "机构",
     "holding_shares_ten_thousand": 3680.00, "holding_ratio": 9.48, "holding_change": "不变"},
    {"stock_code": "300750.SZ", "report_quarter": "2024Q2", "rank_order": 1,
     "shareholder_name": "宁波梅山保税港区瑞庭投资有限公司", "shareholder_type": "机构",
     "holding_shares_ten_thousand": 105657.21, "holding_ratio": 23.99, "holding_change": "不变"},
    {"stock_code": "300750.SZ", "report_quarter": "2024Q2", "rank_order": 2,
     "shareholder_name": "黄世霖", "shareholder_type": "个人",
     "holding_shares_ten_thousand": 52800.00, "holding_ratio": 11.99, "holding_change": "减持"},
    {"stock_code": "000001.SZ", "report_quarter": "2024Q2", "rank_order": 1,
     "shareholder_name": "中国平安保险(集团)股份有限公司", "shareholder_type": "机构",
     "holding_shares_ten_thousand": 258542.00, "holding_ratio": 13.32, "holding_change": "不变"},
    {"stock_code": "000001.SZ", "report_quarter": "2024Q2", "rank_order": 2,
     "shareholder_name": "中国平安人寿保险股份有限公司", "shareholder_type": "机构",
     "holding_shares_ten_thousand": 118060.00, "holding_ratio": 6.08, "holding_change": "不变"},
]

# ── 研报/公告定性文本样本 ──────────────────────────────────────────────
SAMPLE_RESEARCH_REPORTS = [
    {"stock_code": "600519.SH", "source_type": "ANALYST_REPORT", "title": "贵州茅台2024年中报点评：量价齐升，直营占比持续提升",
     "author": "中信证券", "publish_date": "2024-08-15",
     "content": "贵州茅台2024年上半年实现营收834.5亿元，同比增长17.6%，归母净利润416.9亿元，同比增长15.9%。其中茅台酒收入685.3亿元，同比增长15.6%，系列酒收入128.2亿元，同比增长22.8%。直营渠道占比持续提升，i茅台平台贡献增量显著。毛利率维持在91.8%的极高水平，体现出极强的品牌溢价能力。我们维持买入评级，目标价2000元。"},
    {"stock_code": "300750.SZ", "source_type": "ANALYST_REPORT", "title": "宁德时代2024半年报：全球动力电池龙头，海外扩张加速",
     "author": "华泰证券", "publish_date": "2024-08-20",
     "content": "宁德时代2024H1实现营收869.9亿元，动力电池全球市占率维持37.8%的绝对龙头地位。储能业务高速增长，收入占比提升至18%。海外市场毛利率显著高于国内，欧洲建厂进度顺利。麒麟电池与神行超充电池持续获得新客户定点。我们预计2024-2026年EPS分别为5.2/6.3/7.5元，维持增持评级。"},
    {"stock_code": "600276.SH", "source_type": "ANALYST_REPORT", "title": "恒瑞医药：创新药管线进入收获期，国际化进程加速",
     "author": "国泰君安", "publish_date": "2024-09-01",
     "content": "恒瑞医药2024H1创新药收入占比已超过50%，多款核心产品进入医保放量期。PD-1卡瑞利珠单抗海外授权取得重大进展，GLP-1管线临床数据优异。公司研发管线中具备全球竞争力的First-in-class/Best-in-class分子数量在国内药企中名列前茅。预计2024-2026年归母净利润分别为45/55/68亿元，维持买入评级。"},
    {"stock_code": "000333.SZ", "source_type": "EARNINGS_CALL", "title": "美的集团2024年半年度业绩说明会纪要",
     "author": "美的集团", "publish_date": "2024-08-28",
     "content": "2024年上半年集团总营收2172亿元，同比增长10%。ToB业务（楼宇科技、机器人与自动化、新能源及工业技术）收入占比提升至28%。海外市场收入占比超过40%，OBM自有品牌出海战略成效显著。智能家居事业群持续推进高端化与场景化升级。公司承诺未来三年分红率不低于60%，持续为股东创造价值。"},
]


def generate_sample_klines(stock_code, base_price, trend, days=500):
    """生成近2年的模拟日K线时序"""
    klines = []
    current_date = datetime.date(2022, 7, 1)
    price = base_price

    for i in range(days):
        current_date += datetime.timedelta(days=1)
        if current_date.weekday() >= 5:
            continue

        noise = (hash(f"{stock_code}{i}") % 100 - 50) / 2500
        daily_return = trend + noise
        open_p = price * (1 + noise * 0.3)
        high_p = price * (1 + abs(0.01))
        low_p = price * (1 - abs(0.01))
        close_p = price * (1 + daily_return)
        volume = abs(hash(f"vol{stock_code}{i}") % 100000) * 100 + 5000000
        amount = volume * close_p

        klines.append({
            "stock_code": stock_code,
            "trade_date": current_date.strftime("%Y-%m-%d"),
            "open": round(open_p, 2),
            "high": round(high_p, 2),
            "low": round(low_p, 2),
            "close": round(close_p, 2),
            "volume": round(volume, 2),
            "amount": round(amount, 2),
        })
        price = close_p

    return klines


def main():
    print("=" * 60)
    print("A股代表性标的样本数据准备工具")
    print("=" * 60)

    sql_path = os.path.join(OUTPUT_DIR, "sample_stocks_dump.sql")
    with open(sql_path, "w", encoding="utf-8") as f:
        f.write("-- 自动生成的A股代表性标的样本数据\n")
        f.write("-- 包含: 10只核心标的 + K线时序 + 财务季报 + 分红历史 + 十大股东 + 研报向量\n\n")

        # 1. 股票基础信息
        f.write("-- === 1. 股票基础信息 ===\n")
        for s in SAMPLE_STOCKS:
            f.write(
                f"INSERT INTO stock_info (stock_code, stock_name, exchange, industry, pe_ttm, pb, "
                f"market_cap_billion, roe, dividend_yield, listing_date, total_shares, close_price) "
                f"VALUES ('{s['stock_code']}', '{s['stock_name']}', '{s['exchange']}', '{s['industry']}', "
                f"{s['pe_ttm']}, {s['pb']}, {s['market_cap_billion']}, {s['roe']}, {s['dividend_yield']}, "
                f"'{s['listing_date']}', {s['total_shares']}, {s['close_price']}) "
                f"ON CONFLICT (stock_code) DO UPDATE SET pe_ttm=EXCLUDED.pe_ttm, pb=EXCLUDED.pb, "
                f"market_cap_billion=EXCLUDED.market_cap_billion, roe=EXCLUDED.roe;\n"
            )

        # 2. 日K线时序
        f.write("\n-- === 2. 日K线时序 (stock_daily_kline) ===\n")
        kline_configs = [
            ("600519.SH", 1800, 0.0002), ("000858.SZ", 150, 0.0001),
            ("002415.SZ", 35, -0.0001), ("000333.SZ", 55, 0.0003),
            ("300750.SZ", 220, -0.0002), ("600276.SH", 45, 0.0001),
            ("603259.SH", 55, -0.0001), ("300760.SZ", 320, 0.0001),
            ("000661.SZ", 160, -0.0002), ("000001.SZ", 12, 0.0001),
        ]
        total_klines = 0
        for code, base, trend in kline_configs:
            klines = generate_sample_klines(code, base, trend)
            total_klines += len(klines)
            for k in klines:
                f.write(
                    f"INSERT INTO stock_daily_kline (stock_code, trade_date, open, high, low, close, volume, amount) "
                    f"VALUES ('{k['stock_code']}', '{k['trade_date']}', {k['open']}, {k['high']}, "
                    f"{k['low']}, {k['close']}, {k['volume']}, {k['amount']}) "
                    f"ON CONFLICT DO NOTHING;\n"
                )

        # 3. 季度财务指标
        f.write("\n-- === 3. 季度核心财务指标 (stock_financial_quarterly) ===\n")
        for fin in SAMPLE_FINANCIALS:
            cols = ", ".join(fin.keys())
            vals = ", ".join(f"'{v}'" if isinstance(v, str) else str(v) for v in fin.values())
            f.write(f"INSERT INTO stock_financial_quarterly ({cols}) VALUES ({vals}) ON CONFLICT DO NOTHING;\n")

        # 4. 分红历史
        f.write("\n-- === 4. 分红送转历史 (stock_dividend_history) ===\n")
        for d in SAMPLE_DIVIDENDS:
            cols = ", ".join(d.keys())
            vals = ", ".join(f"'{v}'" if isinstance(v, str) else str(v) for v in d.values())
            f.write(f"INSERT INTO stock_dividend_history ({cols}) VALUES ({vals}) ON CONFLICT DO NOTHING;\n")

        # 5. 十大股东
        f.write("\n-- === 5. 十大股东明细 (stock_top_shareholders) ===\n")
        for sh in SAMPLE_SHAREHOLDERS:
            cols = ", ".join(sh.keys())
            vals = ", ".join(f"'{v}'" if isinstance(v, str) else str(v) for v in sh.values())
            f.write(f"INSERT INTO stock_top_shareholders ({cols}) VALUES ({vals}) ON CONFLICT DO NOTHING;\n")

        # 6. 研报向量
        f.write("\n-- === 6. 研报/公告定性文本 (stock_research_vector) ===\n")
        for r in SAMPLE_RESEARCH_REPORTS:
            clean_content = r["content"].replace("'", "''")
            clean_title = r["title"].replace("'", "''")
            f.write(
                f"INSERT INTO stock_research_vector (stock_code, source_type, title, author, publish_date, content) "
                f"VALUES ('{r['stock_code']}', '{r['source_type']}', '{clean_title}', "
                f"'{r['author']}', '{r['publish_date']}', '{clean_content}') "
                f"ON CONFLICT DO NOTHING;\n"
            )

    print(f"\n[SUCCESS] 样本 SQL dump 已生成: {sql_path}")
    print(f"  - 10 只核心 A 股标的")
    print(f"  - {total_klines}+ 条日K线时序记录")
    print(f"  - {len(SAMPLE_FINANCIALS)} 条季度财务指标")
    print(f"  - {len(SAMPLE_DIVIDENDS)} 条分红历史")
    print(f"  - {len(SAMPLE_SHAREHOLDERS)} 条十大股东明细")
    print(f"  - {len(SAMPLE_RESEARCH_REPORTS)} 条研报定性文本")


if __name__ == "__main__":
    main()
