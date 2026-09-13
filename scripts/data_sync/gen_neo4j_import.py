#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Neo4j Cypher 导入脚本生成器
============================
从 wind_demo_data.json 和 market_basics_data.json 生成 Cypher 导入文件。

图模型设计:
  (Company)  <-[:MANAGED_BY]-  (Fund)  -[:MANAGED_BY]->  (Manager)
  (Fund)     -[:HOLDS]->        (Stock) -[:BELONGS_TO]-> (Industry)
  (Stock)    -[:LISTED_ON]->    (Exchange)
  (Fund)     -[:HAS_TYPE]->      (FundType)
"""

import json
import os
from pathlib import Path

OUTPUT_DIR = Path(__file__).parent
NEO4J_IMPORT = OUTPUT_DIR / "neo4j_import.cypher"


def safe_str(val, default=''):
    if val is None or str(val).strip() in ('', 'None', 'nan'):
        return default
    return str(val).strip().replace("\\", "\\\\").replace("'", "\\'")


def safe_float(val, default=0.0):
    try:
        if val is None or str(val).strip() in ('', 'None', 'nan'):
            return default
        return float(val)
    except (TypeError, ValueError):
        return default


def generate_fund_graph(fund_data):
    """生成基金知识图谱 Cypher 语句"""
    lines = []
    lines.append("// ============================================================")
    lines.append("// 金融基金知识图谱 - Neo4j Cypher 导入")
    lines.append("// ============================================================")
    lines.append("")
    lines.append("// 清空数据库")
    lines.append("MATCH (n) DETACH DELETE n;")
    lines.append("")

    # ── 1. 基金公司节点 ──────────────────────────────────────
    lines.append("// --- 基金公司节点 ---")
    for c in fund_data.get("companies", []):
        lines.append(
            f"MERGE (c:Company {{company_id: '{safe_str(c['company_id'])}'}}) "
            f"SET c.name = '{safe_str(c.get('company_name', ''))}', "
            f"c.short_name = '{safe_str(c.get('short_name', ''))}', "
            f"c.establishment_date = '{safe_str(c.get('establishment_date', ''))}', "
            f"c.total_scale_billion = {safe_float(c.get('total_scale_billion'))}, "
            f"c.equity_scale_billion = {safe_float(c.get('equity_scale_billion'))}, "
            f"c.manager_count = {c.get('manager_count', 0)}, "
            f"c.fund_count = {c.get('fund_count', 0)};"
        )
    lines.append(f"// 公司节点: {len(fund_data.get('companies', []))} 个")
    lines.append("")

    # ── 2. 基金经理节点 ──────────────────────────────────────
    lines.append("// --- 基金经理节点 ---")
    for m in fund_data.get("managers", []):
        lines.append(
            f"MERGE (m:Manager {{manager_id: '{safe_str(m['manager_id'])}'}}) "
            f"SET m.name = '{safe_str(m.get('manager_name', ''))}', "
            f"m.gender = '{safe_str(m.get('gender', ''))}', "
            f"m.education = '{safe_str(m.get('education', ''))}', "
            f"m.working_days = {m.get('working_days', 0)}, "
            f"m.current_total_scale_billion = {safe_float(m.get('current_total_scale_billion'))}, "
            f"m.best_fund_code = '{safe_str(m.get('best_fund_code', ''))}', "
            f"m.best_fund_return = {safe_float(m.get('best_fund_return'))};"
        )
    lines.append(f"// 经理节点: {len(fund_data.get('managers', []))} 个")
    lines.append("")

    # ── 3. 基金类型节点 ───────────────────────────────────────
    lines.append("// --- 基金类型节点 ---")
    fund_types = set()
    for f in fund_data.get("funds", []):
        ft = safe_str(f.get("fund_type", ""))
        if ft:
            fund_types.add(ft)
    for ft in fund_types:
        lines.append(f"MERGE (t:FundType {{name: '{safe_str(ft)}'}});")
    lines.append(f"// 类型节点: {len(fund_types)} 个")
    lines.append("")

    # ── 4. 基金节点 ──────────────────────────────────────────
    lines.append("// --- 基金节点 ---")
    for f in fund_data.get("funds", []):
        lines.append(
            f"MERGE (f:Fund {{fund_code: '{safe_str(f['fund_code'])}'}}) "
            f"SET f.name = '{safe_str(f.get('fund_name', ''))}', "
            f"f.fund_type = '{safe_str(f.get('fund_type', ''))}', "
            f"f.establishment_date = '{safe_str(f.get('establishment_date', ''))}', "
            f"f.current_scale_billion = {safe_float(f.get('current_scale_billion'))}, "
            f"f.tracking_benchmark = '{safe_str(f.get('tracking_benchmark', ''))[:200]}', "
            f"f.custodian_bank = '{safe_str(f.get('custodian_bank', ''))}';"
        )
    lines.append(f"// 基金节点: {len(fund_data.get('funds', []))} 个")
    lines.append("")

    # ── 5. 股票节点 (从 holdings 中提取) ──────────────────────
    lines.append("// --- 股票节点 ---")
    stocks = {}
    for h in fund_data.get("holdings", []):
        sc = safe_str(h.get("stock_code", ""))
        if sc and sc not in stocks:
            stocks[sc] = {
                "stock_code": sc,
                "stock_name": safe_str(h.get("stock_name", "")),
                "holding_sector": safe_str(h.get("holding_sector", "")),
            }
    for sc, s in stocks.items():
        lines.append(
            f"MERGE (s:Stock {{stock_code: '{safe_str(sc)}'}}) "
            f"SET s.name = '{safe_str(s.get('stock_name', ''))}', "
            f"s.sector = '{safe_str(s.get('holding_sector', ''))}';"
        )
    lines.append(f"// 股票节点: {len(stocks)} 个")
    lines.append("")

    # ── 6. 行业节点 ──────────────────────────────────────────
    lines.append("// --- 行业节点 ---")
    sectors = set()
    for s in stocks.values():
        sec = s.get("holding_sector", "")
        if sec:
            sectors.add(sec)
    for sec in sectors:
        lines.append(f"MERGE (i:Industry {{name: '{safe_str(sec)}'}});")
    lines.append(f"// 行业节点: {len(sectals) if 'sectals' in dir() else len(sectors)} 个")
    lines.append("")

    # ── 7. 关系: Fund -[:BELONGS_TO]-> Company ────────────────
    lines.append("// --- 关系: Fund -> Company ---")
    for f in fund_data.get("funds", []):
        cid = safe_str(f.get("management_company_id", ""))
        fc = safe_str(f.get("fund_code", ""))
        if cid and fc:
            lines.append(
                f"MATCH (f:Fund {{fund_code: '{fc}'}}), (c:Company {{company_id: '{cid}'}}) "
                f"MERGE (f)-[:BELONGS_TO]->(c);"
            )
    lines.append("")

    # ── 8. 关系: Fund -[:MANAGED_BY]-> Manager ───────────────
    lines.append("// --- 关系: Fund -> Manager ---")
    for mm in fund_data.get("manager_mappings", []):
        fc = safe_str(mm.get("fund_code", ""))
        mid = safe_str(mm.get("manager_id", ""))
        is_current = mm.get("is_current", True)
        start = safe_str(mm.get("start_date", ""))
        end = safe_str(mm.get("end_date", ""))
        tenure = safe_float(mm.get("tenure_return"))
        if fc and mid:
            rel_type = "MANAGED_BY" if is_current else "MANAGED_BY_HIST"
            lines.append(
                f"MATCH (f:Fund {{fund_code: '{fc}'}}), (m:Manager {{manager_id: '{mid}'}}) "
                f"MERGE (f)-[r:{rel_type}]->(m) "
                f"SET r.start_date = '{start}', r.end_date = '{end}', "
                f"r.is_current = {str(is_current).lower()}, r.tenure_return = {tenure};"
            )
    lines.append("")

    # ── 9. 关系: Fund -[:HAS_TYPE]-> FundType ────────────────
    lines.append("// --- 关系: Fund -> FundType ---")
    for f in fund_data.get("funds", []):
        fc = safe_str(f.get("fund_code", ""))
        ft = safe_str(f.get("fund_type", ""))
        if fc and ft:
            lines.append(
                f"MATCH (f:Fund {{fund_code: '{fc}'}}), (t:FundType {{name: '{ft}'}}) "
                f"MERGE (f)-[:HAS_TYPE]->(t);"
            )
    lines.append("")

    # ── 10. 关系: Fund -[:HOLDS]-> Stock ──────────────────────
    lines.append("// --- 关系: Fund -> Stock (季度重仓) ---")
    for h in fund_data.get("holdings", []):
        fc = safe_str(h.get("fund_code", ""))
        sc = safe_str(h.get("stock_code", ""))
        rq = safe_str(h.get("report_quarter", ""))
        rank = h.get("rank_order", 0)
        ratio = safe_float(h.get("holding_ratio"))
        shares = safe_float(h.get("holding_shares_ten_thousand"))
        if fc and sc:
            lines.append(
                f"MATCH (f:Fund {{fund_code: '{fc}'}}), (s:Stock {{stock_code: '{sc}'}}) "
                f"MERGE (f)-[r:HOLDS {{report_quarter: '{rq}'}}]->(s) "
                f"SET r.rank_order = {rank}, r.holding_ratio = {ratio}, "
                f"r.holding_shares_wan = {shares};"
            )
    lines.append("")

    # ── 11. 关系: Stock -[:BELONGS_TO]-> Industry ────────────
    lines.append("// --- 关系: Stock -> Industry ---")
    for sc, s in stocks.items():
        sec = s.get("holding_sector", "")
        if sc and sec:
            lines.append(
                f"MATCH (s:Stock {{stock_code: '{safe_str(sc)}'}}), (i:Industry {{name: '{safe_str(sec)}'}}) "
                f"MERGE (s)-[:BELONGS_TO]->(i);"
            )
    lines.append("")

    # ── 12. 关系: Manager -[:WORKS_AT]-> Company ──────────────
    lines.append("// --- 关系: Manager -> Company ---")
    for m in fund_data.get("managers", []):
        mid = safe_str(m.get("manager_id", ""))
        cid = safe_str(m.get("company_id", ""))
        if mid and cid:
            lines.append(
                f"MATCH (m:Manager {{manager_id: '{mid}'}}), (c:Company {{company_id: '{cid}'}}) "
                f"MERGE (m)-[:WORKS_AT]->(c);"
            )
    lines.append("")

    return lines


def generate_market_graph(market_data):
    """生成市场基础数据图 (股票/期货/企业)"""
    lines = []
    lines.append("// ============================================================")
    lines.append("// 市场基础数据 - Neo4j Cypher 导入")
    lines.append("// ============================================================")
    lines.append("")

    # ── 交易所节点 ────────────────────────────────────────────
    lines.append("// --- 交易所节点 ---")
    exchanges = set()
    for s in market_data.get("stocks", []):
        exchanges.add(safe_str(s.get("exchange", "")))
    for ex in exchanges:
        if ex:
            lines.append(f"MERGE (e:Exchange {{name: '{safe_str(ex)}'}});")
    lines.append("")

    # ── 股票节点 (扩展) ────────────────────────────────────────
    lines.append("// --- 股票节点 (市场数据) ---")
    for s in market_data.get("stocks", []):
        lines.append(
            f"MERGE (s:Stock {{stock_code: '{safe_str(s['code'])}'}}) "
            f"SET s.name = '{safe_str(s.get('name', ''))}', "
            f"s.exchange = '{safe_str(s.get('exchange', ''))}', "
            f"s.industry = '{safe_str(s.get('industry', '未分类'))}', "
            f"s.ipo_date = '{safe_str(s.get('ipo_date', ''))}', "
            f"s.pe_ttm = {safe_float(s.get('pe_ttm'))}, "
            f"s.pb = {safe_float(s.get('pb'))}, "
            f"s.roe = {safe_float(s.get('roe'))}, "
            f"s.total_shares = {safe_float(s.get('total_shares'))}, "
            f"s.close = {safe_float(s.get('close'))}, "
            f"s.market_cap_billion = {safe_float(s.get('market_cap_billion'))};"
        )
    lines.append(f"// 股票更新: {len(market_data.get('stocks', []))} 只")
    lines.append("")

    # ── 期货合约节点 ──────────────────────────────────────────
    lines.append("// --- 期货合约节点 ---")
    for fu in market_data.get("futures", []):
        lines.append(
            f"MERGE (f:Futures {{code: '{safe_str(fu['code'])}'}}) "
            f"SET f.name = '{safe_str(fu.get('name', ''))}', "
            f"f.exchange = '{safe_str(fu.get('exchange', ''))}', "
            f"f.category = '{safe_str(fu.get('category', ''))}', "
            f"f.underlying = '{safe_str(fu.get('underlying', ''))}';"
        )
    lines.append(f"// 期货节点: {len(market_data.get('futures', []))} 个")
    lines.append("")

    # ── 理财产品节点 ──────────────────────────────────────────
    lines.append("// --- 理财产品节点 ---")
    for wp in market_data.get("wealth_products", []):
        lines.append(
            f"MERGE (w:WealthProduct {{product_code: '{safe_str(wp['product_code'])}'}}) "
            f"SET w.name = '{safe_str(wp.get('product_name', ''))}', "
            f"w.bank_name = '{safe_str(wp.get('bank_name', ''))}', "
            f"w.product_type = '{safe_str(wp.get('product_type', ''))}', "
            f"w.risk_level = '{safe_str(wp.get('risk_level', ''))}', "
            f"w.expected_annual_return = {safe_float(wp.get('expected_annual_return'))}, "
            f"w.min_purchase_amount = {safe_float(wp.get('min_purchase_amount'))}, "
            f"w.product_term_days = {wp.get('product_term_days', 0)}, "
            f"w.start_date = '{safe_str(wp.get('start_date', ''))}', "
            f"w.end_date = '{safe_str(wp.get('end_date', ''))}';"
        )
    lines.append(f"// 理财节点: {len(market_data.get('wealth_products', []))} 个")
    lines.append("")

    # ── 企业节点 ──────────────────────────────────────────────
    lines.append("// --- 上市企业节点 ---")
    for e in market_data.get("enterprises", []):
        lines.append(
            f"MERGE (ent:Enterprise {{enterprise_id: '{safe_str(e['enterprise_id'])}'}}) "
            f"SET ent.name = '{safe_str(e.get('enterprise_name', ''))}', "
            f"ent.stock_code = '{safe_str(e.get('stock_code', ''))}', "
            f"ent.exchange = '{safe_str(e.get('exchange', ''))}', "
            f"ent.industry = '{safe_str(e.get('industry', '未分类'))}', "
            f"ent.listing_date = '{safe_str(e.get('listing_date', ''))}', "
            f"ent.total_shares = {safe_float(e.get('total_shares'))}, "
            f"ent.market_cap_billion = {safe_float(e.get('market_cap_billion'))};"
        )
    lines.append(f"// 企业节点: {len(market_data.get('enterprises', []))} 家")
    lines.append("")

    # ── 关系: Stock -[:LISTED_ON]-> Exchange ─────────────────
    lines.append("// --- 关系: Stock -> Exchange ---")
    for s in market_data.get("stocks", []):
        sc = safe_str(s.get("code", ""))
        ex = safe_str(s.get("exchange", ""))
        if sc and ex:
            lines.append(
                f"MATCH (s:Stock {{stock_code: '{sc}'}}), (e:Exchange {{name: '{ex}'}}) "
                f"MERGE (s)-[:LISTED_ON]->(e);"
            )
    lines.append("")

    # ── 关系: Enterprise -[:HAS_STOCK]-> Stock ───────────────
    lines.append("// --- 关系: Enterprise -> Stock ---")
    for e in market_data.get("enterprises", []):
        eid = safe_str(e.get("enterprise_id", ""))
        sc = safe_str(e.get("stock_code", ""))
        if eid and sc:
            lines.append(
                f"MATCH (ent:Enterprise {{enterprise_id: '{eid}'}}), (s:Stock {{stock_code: '{sc}'}}) "
                f"MERGE (ent)-[:HAS_STOCK]->(s);"
            )
    lines.append("")

    # ── 创建索引 ──────────────────────────────────────────────
    lines.append("// --- 创建索引 ---")
    lines.append("CREATE INDEX fund_code IF NOT EXISTS FOR (f:Fund) ON (f.fund_code);")
    lines.append("CREATE INDEX company_id IF NOT EXISTS FOR (c:Company) ON (c.company_id);")
    lines.append("CREATE INDEX manager_id IF NOT EXISTS FOR (m:Manager) ON (m.manager_id);")
    lines.append("CREATE INDEX stock_code IF NOT EXISTS FOR (s:Stock) ON (s.stock_code);")
    lines.append("CREATE INDEX fund_type IF NOT EXISTS FOR (t:FundType) ON (t.name);")
    lines.append("CREATE INDEX industry_name IF NOT EXISTS FOR (i:Industry) ON (i.name);")
    lines.append("CREATE INDEX futures_code IF NOT EXISTS FOR (f:Futures) ON (f.code);")
    lines.append("CREATE INDEX wealth_code IF NOT EXISTS FOR (w:WealthProduct) ON (w.product_code);")
    lines.append("CREATE INDEX enterprise_id IF NOT EXISTS FOR (ent:Enterprise) ON (ent.enterprise_id);")
    lines.append("")

    return lines


def main():
    print("=" * 60)
    print("Neo4j Cypher 导入脚本生成器")
    print("=" * 60)

    all_lines = []

    # 基金数据
    fund_file = OUTPUT_DIR / "wind_demo_data.json"
    if fund_file.exists():
        print("\n[1] 加载基金数据...", flush=True)
        with open(fund_file, encoding='utf-8') as f:
            fund_data = json.load(f)
        all_lines.extend(generate_fund_graph(fund_data))
        print(f"  基金: {len(fund_data.get('funds', []))} | 公司: {len(fund_data.get('companies', []))} | 经理: {len(fund_data.get('managers', []))}")
    else:
        print("  基金数据文件不存在，跳过")

    # 市场基础数据
    market_file = OUTPUT_DIR / "market_basics_data.json"
    if market_file.exists():
        print("\n[2] 加载市场基础数据...", flush=True)
        with open(market_file, encoding='utf-8') as f:
            market_data = json.load(f)
        all_lines.extend(generate_market_graph(market_data))
        print(f"  股票: {len(market_data.get('stocks', []))} | 期货: {len(market_data.get('futures', []))} | 理财: {len(market_data.get('wealth_products', []))} | 企业: {len(market_data.get('enterprises', []))}")
    else:
        print("  市场基础数据文件不存在，跳过")

    # 写入文件
    print(f"\n[3] 生成 Cypher 文件...", flush=True)
    with open(NEO4J_IMPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(all_lines))
    size_kb = NEO4J_IMPORT.stat().st_size / 1024
    print(f"  输出: {NEO4J_IMPORT.name} ({size_kb:.0f} KB)")
    print(f"  总行数: {len(all_lines)}")

    # 同时复制到 Neo4j import 目录
    neo4j_import_dir = Path(__file__).parent.parent.parent / "docker" / "postgres" / "neo4j" / "import"
    neo4j_import_dir.mkdir(parents=True, exist_ok=True)
    import_file = neo4j_import_dir / "neo4j_import.cypher"
    with open(import_file, "w", encoding="utf-8") as f:
        f.write("\n".join(all_lines))
    print(f"  已复制到: {import_file}")

    print("\n完成!")


if __name__ == "__main__":
    main()
