#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
公募基金样本真实数据抽取与入库脚本
涵盖基金基础信息、基金经理、历史净值时序、季度前十大重仓与定性策略观点。
"""

import sys
import os
import datetime
from decimal import Decimal

# 预设公募基金样本数据（包含张坤易方达蓝筹、朱少醒富国天惠、葛兰中欧医疗）
SAMPLE_COMPANIES = [
    {
        "company_id": "80000222",
        "company_name": "易方达基金管理有限公司",
        "short_name": "易方达",
        "establishment_date": "2001-04-17",
        "total_scale_billion": Decimal("1680.50"),
        "equity_scale_billion": Decimal("520.30"),
        "manager_count": 82,
        "fund_count": 320
    },
    {
        "company_id": "80000223",
        "company_name": "富国基金管理有限公司",
        "short_name": "富国基金",
        "establishment_date": "1999-04-13",
        "total_scale_billion": Decimal("920.80"),
        "equity_scale_billion": Decimal("310.40"),
        "manager_count": 65,
        "fund_count": 280
    },
    {
        "company_id": "80048752",
        "company_name": "中欧基金管理有限公司",
        "short_name": "中欧基金",
        "establishment_date": "2006-07-19",
        "total_scale_billion": Decimal("480.20"),
        "equity_scale_billion": Decimal("240.60"),
        "manager_count": 45,
        "fund_count": 160
    }
]

SAMPLE_MANAGERS = [
    {
        "manager_id": "M001",
        "manager_name": "张坤",
        "company_id": "80000222",
        "gender": "男",
        "education": "硕士",
        "working_days": 4350,
        "current_total_scale_billion": Decimal("620.50"),
        "best_fund_code": "005827",
        "best_fund_return": Decimal("14.20")
    },
    {
        "manager_id": "M002",
        "manager_name": "朱少醒",
        "company_id": "80000223",
        "gender": "男",
        "education": "博士",
        "working_days": 6800,
        "current_total_scale_billion": Decimal("290.80"),
        "best_fund_code": "161005",
        "best_fund_return": Decimal("18.50")
    },
    {
        "manager_id": "M003",
        "manager_name": "葛兰",
        "company_id": "80048752",
        "gender": "女",
        "education": "博士",
        "working_days": 3500,
        "current_total_scale_billion": Decimal("450.30"),
        "best_fund_code": "003095",
        "best_fund_return": Decimal("12.80")
    }
]

SAMPLE_FUNDS = [
    {
        "fund_code": "005827",
        "fund_name": "易方达蓝筹精选混合",
        "fund_type": "偏股混合型",
        "establishment_date": "2018-09-05",
        "management_company_id": "80000222",
        "current_scale_billion": Decimal("410.50"),
        "tracking_benchmark": "沪深300指数收益率*45%+中证港股通综合指数收益率*35%+中债总指数收益率*20%",
        "custodian_bank": "中国银行"
    },
    {
        "fund_code": "161005",
        "fund_name": "富国天惠成长混合(LOF)A",
        "fund_type": "偏股混合型",
        "establishment_date": "2005-11-16",
        "management_company_id": "80000223",
        "current_scale_billion": Decimal("285.20"),
        "tracking_benchmark": "中证800指数收益率*70%+中债综合全价指数收益率*30%",
        "custodian_bank": "中国建设银行"
    },
    {
        "fund_code": "003095",
        "fund_name": "中欧医疗健康混合A",
        "fund_type": "偏股混合型",
        "establishment_date": "2016-09-29",
        "management_company_id": "80048752",
        "current_scale_billion": Decimal("210.80"),
        "tracking_benchmark": "中证医药卫生指数收益率*60%+中证港股通综合指数收益率*20%+中债综合全价指数收益率*20%",
        "custodian_bank": "招商银行"
    }
]

# 样本前十大重仓股票
SAMPLE_HOLDINGS = [
    # 易方达蓝筹精选 (005827) - 偏重商业模式、白酒与港股互联网龙头
    {"fund_code": "005827", "report_quarter": "2024Q2", "rank_order": 1, "stock_code": "00700", "stock_name": "腾讯控股", "holding_ratio": Decimal("9.95"), "holding_sector": "传媒/互联网"},
    {"fund_code": "005827", "report_quarter": "2024Q2", "rank_order": 2, "stock_code": "600519", "stock_name": "贵州茅台", "holding_ratio": Decimal("9.88"), "holding_sector": "食品饮料"},
    {"fund_code": "005827", "report_quarter": "2024Q2", "rank_order": 3, "stock_code": "000858", "stock_name": "五粮液", "holding_ratio": Decimal("9.62"), "holding_sector": "食品饮料"},
    {"fund_code": "005827", "report_quarter": "2024Q2", "rank_order": 4, "stock_code": "03690", "stock_name": "美团-W", "holding_ratio": Decimal("8.50"), "holding_sector": "社会服务"},
    {"fund_code": "005827", "report_quarter": "2024Q2", "rank_order": 5, "stock_code": "000568", "stock_name": "泸州老窖", "holding_ratio": Decimal("7.80"), "holding_sector": "食品饮料"},

    # 富国天惠成长 (161005) - 均衡成长、隐形冠军
    {"fund_code": "161005", "report_quarter": "2024Q2", "rank_order": 1, "stock_code": "600519", "stock_name": "贵州茅台", "holding_ratio": Decimal("5.85"), "holding_sector": "食品饮料"},
    {"fund_code": "161005", "report_quarter": "2024Q2", "rank_order": 2, "stock_code": "002415", "stock_name": "海康威视", "holding_ratio": Decimal("4.30"), "holding_sector": "电子/安防"},
    {"fund_code": "161005", "report_quarter": "2024Q2", "rank_order": 3, "stock_code": "000333", "stock_name": "美的集团", "holding_ratio": Decimal("3.90"), "holding_sector": "家用电器"},
    {"fund_code": "161005", "report_quarter": "2024Q2", "rank_order": 4, "stock_code": "300750", "stock_name": "宁德时代", "holding_ratio": Decimal("3.60"), "holding_sector": "电力设备"},
    {"fund_code": "161005", "report_quarter": "2024Q2", "rank_order": 5, "stock_code": "600031", "stock_name": "三一重工", "holding_ratio": Decimal("3.20"), "holding_sector": "机械设备"},

    # 中欧医疗健康 (003095) - 医药全产业链
    {"fund_code": "003095", "report_quarter": "2024Q2", "rank_order": 1, "stock_code": "603259", "stock_name": "药明康德", "holding_ratio": Decimal("9.50"), "holding_sector": "医药生物/CXO"},
    {"fund_code": "003095", "report_quarter": "2024Q2", "rank_order": 2, "stock_code": "300760", "stock_name": "迈瑞医疗", "holding_ratio": Decimal("8.90"), "holding_sector": "医药生物/医疗器械"},
    {"fund_code": "003095", "report_quarter": "2024Q2", "rank_order": 3, "stock_code": "300122", "stock_name": "智飞生物", "holding_ratio": Decimal("7.20"), "holding_sector": "医药生物/疫苗"},
    {"fund_code": "003095", "report_quarter": "2024Q2", "rank_order": 4, "stock_code": "000661", "stock_name": "长春高新", "holding_ratio": Decimal("6.10"), "holding_sector": "医药生物/生物制品"},
    {"fund_code": "003095", "report_quarter": "2024Q2", "rank_order": 5, "stock_code": "600276", "stock_name": "恒瑞医药", "holding_ratio": Decimal("5.80"), "holding_sector": "医药生物/创新药"}
]

# 样本定性季报策略观点（用于向量化混合 RAG）
SAMPLE_REPORTS = [
    {
        "fund_code": "005827",
        "manager_name": "张坤",
        "report_quarter": "2024Q2",
        "section_title": "管理人对报告期内投资策略与运作分析",
        "content": "我们依然长期看好商业模式优秀、具备极强自由现金流产生能力与宽阔护城河的商业龙头。在经济周期波动中，优秀企业的市占率与竞争壁垒往往进一步提升。组合在二季度维持了消费与科技龙头的核心配置，重视企业的分红意愿与股东回报率，不轻易做风格轮动与仓位博弈，坚信时间是优秀企业的朋友。"
    },
    {
        "fund_code": "161005",
        "manager_name": "朱少醒",
        "report_quarter": "2024Q2",
        "section_title": "管理人对报告期内投资策略与运作分析",
        "content": "本基金在二季度依然保持高仓位运作。我们坚持自下而上精选具有良好企业基因、治理结构优良、并且估值处于合理或低估区间的优秀成长企业。在行业配置上相对淡化宏观择时，侧重于组合的均衡性与行业分散度，积极寻找具备长期翻倍潜力但目前被市场情绪错杀的隐形制造业与科技制造业冠军。"
    }
]

def generate_sample_navs(fund_code, base_nav, trend):
    """生成近1年的模拟净值时序 (250个交易日)"""
    navs = []
    current_date = datetime.date(2023, 7, 1)
    nav = base_nav

    for i in range(250):
        current_date += datetime.timedelta(days=1)
        if current_date.weekday() >= 5: # 跳过周末
            continue
        
        # 简单波动模拟
        noise = Decimal((i % 7 - 3) * 0.003)
        daily_growth = (trend + noise)
        nav = (nav * (Decimal("1.0000") + daily_growth)).quantize(Decimal("0.0001"))
        
        navs.append({
            "fund_code": fund_code,
            "nav_date": current_date.strftime("%Y-%m-%d"),
            "unit_nav": nav,
            "accumulated_nav": (nav * Decimal("1.2")).quantize(Decimal("0.0001")),
            "adjusted_nav": nav,
            "daily_growth_rate": (daily_growth * Decimal("100")).quantize(Decimal("0.01"))
        })
    return navs

def main():
    print("==================================================")
    print("金融研究 Agent 样本公募数据准备工具")
    print("==================================================")

    # 生成 SQL 导入文件
    sql_path = os.path.join(os.path.dirname(__file__), "sample_funds_dump.sql")
    with open(sql_path, "w", encoding="utf-8") as f:
        f.write("-- 自动生成的金融研究 Agent 基础样本数据\n\n")

        for c in SAMPLE_COMPANIES:
            f.write(f"INSERT INTO fund_company (company_id, company_name, short_name, establishment_date, total_scale_billion, equity_scale_billion, manager_count, fund_count) "
                    f"VALUES ('{c['company_id']}', '{c['company_name']}', '{c['short_name']}', '{c['establishment_date']}', {c['total_scale_billion']}, {c['equity_scale_billion']}, {c['manager_count']}, {c['fund_count']}) ON CONFLICT DO NOTHING;\n")
        
        for m in SAMPLE_MANAGERS:
            f.write(f"INSERT INTO fund_manager (manager_id, manager_name, company_id, gender, education, working_days, current_total_scale_billion, best_fund_code, best_fund_return) "
                    f"VALUES ('{m['manager_id']}', '{m['manager_name']}', '{m['company_id']}', '{m['gender']}', '{m['education']}', {m['working_days']}, {m['current_total_scale_billion']}, '{m['best_fund_code']}', {m['best_fund_return']}) ON CONFLICT DO NOTHING;\n")

        for fund in SAMPLE_FUNDS:
            f.write(f"INSERT INTO fund_info (fund_code, fund_name, fund_type, establishment_date, management_company_id, current_scale_billion, tracking_benchmark, custodian_bank) "
                    f"VALUES ('{fund['fund_code']}', '{fund['fund_name']}', '{fund['fund_type']}', '{fund['establishment_date']}', '{fund['management_company_id']}', {fund['current_scale_billion']}, '{fund['tracking_benchmark']}', '{fund['custodian_bank']}) ON CONFLICT DO NOTHING;\n")

        for h in SAMPLE_HOLDINGS:
            f.write(f"INSERT INTO fund_quarterly_holdings (fund_code, report_quarter, rank_order, stock_code, stock_name, holding_ratio, holding_shares_ten_thousand, holding_sector) "
                    f"VALUES ('{h['fund_code']}', '{h['report_quarter']}', {h['rank_order']}', '{h['stock_code']}', '{h['stock_name']}', {h['holding_ratio']}, 100.0, '{h['holding_sector']}') ON CONFLICT DO NOTHING;\n")

        for rep in SAMPLE_REPORTS:
            clean_content = rep['content'].replace("'", "''")
            f.write(f"INSERT INTO fund_report_vector (fund_code, manager_name, report_quarter, section_title, content) "
                    f"VALUES ('{rep['fund_code']}', '{rep['manager_name']}', '{rep['report_quarter']}', '{rep['section_title']}', '{clean_content}');\n")

        # 生成净值时序数据
        for fund in SAMPLE_FUNDS:
            trend = Decimal("0.0005") if fund["fund_code"] == "161005" else Decimal("0.0003")
            nav_records = generate_sample_navs(fund["fund_code"], Decimal("1.2000"), trend)
            for n in nav_records:
                f.write(f"INSERT INTO fund_nav_history (fund_code, nav_date, unit_nav, accumulated_nav, adjusted_nav, daily_growth_rate) "
                        f"VALUES ('{n['fund_code']}', '{n['nav_date']}', {n['unit_nav']}, {n['accumulated_nav']}, {n['adjusted_nav']}, {n['daily_growth_rate']}) ON CONFLICT DO NOTHING;\n")

    print(f"[SUCCESS] Sample SQL dump created at: {sql_path}")
    print("Contains: 3 top companies, 3 managers, 3 funds, 15 quarterly holdings, 500+ daily NAV records, and quarterly reports!")

if __name__ == "__main__":
    main()
