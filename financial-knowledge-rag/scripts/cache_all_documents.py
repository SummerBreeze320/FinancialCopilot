"""全量解析并本地缓存所有 5 份核心文档及其切分结果。

涵盖用户提供的核心投研披露：
1. 诺安成长混合型证券投资基金2026年第2季度报告 (2026Q2)
2. 诺安成长混合型证券投资基金2026年第1季度报告 (2026Q1)
3. 诺安基金管理有限公司关于旗下基金持有的股票估值调整的公告 (拓荆科技 688072 估值调整)
4. 诺安成长混合型证券投资基金基金产品资料概要更新 (费率与投资策略)
5. 诺安成长混合型证券投资基金2026年中期报告 (2026H1 深度中报)
"""

import json
import os
import re
import sys

sys.path.insert(0, ".")
sys.stdout.reconfigure(encoding="utf-8")

from app.ingest.pdf_parser import PdfParser

DOC_CONFIGS = [
    {
        "doc_id": "doc_4102085",
        "title": "诺安成长混合型证券投资基金2026年第2季度报告",
        "file_path": r"D:\Users\longlong\Downloads\诺安成长混合型证券投资基金2026 年第 2 季度报告2026 年 06 月 30 日.pdf",
        "cognitive_layer": "GROUND_TRUTH",
        "category_path": "GROUND_TRUTH.PERIODIC.FUND_PERIODIC",
        "institution": "诺安基金管理有限公司",
        "publish_date": "2026-07-21",
        "report_period": "2026Q2",
        "prefix": "chunk_q2"
    },
    {
        "doc_id": "doc_4102084",
        "title": "诺安成长混合型证券投资基金2026年第1季度报告",
        "file_path": r"D:\Users\longlong\Downloads\诺安成长混合型证券投资基金2026 年第 1 季度报告 2026 年 03 月 31.pdf",
        "cognitive_layer": "GROUND_TRUTH",
        "category_path": "GROUND_TRUTH.PERIODIC.FUND_PERIODIC",
        "institution": "诺安基金管理有限公司",
        "publish_date": "2026-04-22",
        "report_period": "2026Q1",
        "prefix": "chunk_q1"
    },
    {
        "doc_id": "doc_ann_val_adj",
        "title": "诺安基金管理有限公司关于旗下基金持有的股票估值调整的公告",
        "file_path": r"D:\Users\longlong\Downloads\诺安基金管理有限公司关于旗下基金持有的股票估值调整的公告.pdf",
        "cognitive_layer": "GROUND_TRUTH",
        "category_path": "GROUND_TRUTH.ANNOUNCEMENT.VALUATION",
        "institution": "诺安基金管理有限公司",
        "publish_date": "2026-06-30",
        "report_period": "2026-06",
        "prefix": "chunk_ann"
    },
    {
        "doc_id": "doc_fund_summary",
        "title": "诺安成长混合型证券投资基金基金产品资料概要更新",
        "file_path": r"D:\Users\longlong\Downloads\诺安成长混合型证券投资基金基金产品资料概要更新.pdf",
        "cognitive_layer": "GROUND_TRUTH",
        "category_path": "GROUND_TRUTH.PROFILE.KEY_FACTS",
        "institution": "诺安基金管理有限公司",
        "publish_date": "2026-07-27",
        "report_period": "2026-07",
        "prefix": "chunk_kfs"
    },
    {
        "doc_id": "doc_semi_annual_2026",
        "title": "诺安成长混合型证券投资基金2026年中期报告",
        "file_path": r"D:\Users\longlong\Downloads\诺安成长混合型证券投资基金2026年中期报告 2026年 06 月 30 日.pdf",
        "cognitive_layer": "GROUND_TRUTH",
        "category_path": "GROUND_TRUTH.PERIODIC.FUND_PERIODIC",
        "institution": "诺安基金管理有限公司",
        "publish_date": "2026-08-28",
        "report_period": "2026H1",
        "prefix": "chunk_h1"
    }
]


def run():
    print("=" * 70)
    print("🚀 开始全量多文档解析与切分结果持久化缓存...")
    print("=" * 70)

    os.makedirs("data", exist_ok=True)
    parser = PdfParser()
    docs_result = []
    chunks_result = []

    for cfg in DOC_CONFIGS:
        fpath = cfg["file_path"]
        if not os.path.exists(fpath):
            print(f"⚠️ 文件未找到，跳过: {fpath}")
            continue

        fsize = os.path.getsize(fpath)
        base_meta = {
            "doc_id": cfg["doc_id"],
            "doc_title": cfg["title"],
            "institution": cfg["institution"],
            "publish_date": cfg["publish_date"],
            "cognitive_layer": cfg["cognitive_layer"],
            "category_path": cfg["category_path"],
            "report_period": cfg["report_period"]
        }

        print(f"\n📄 正在解析: 【{cfg['title']}】")
        chunks = parser.parse_file(fpath, metadata=base_meta)
        tbl_cnt = sum(1 for c in chunks if c.has_table)
        chart_cnt = sum(1 for c in chunks if c.has_chart)
        text_cnt = len(chunks) - tbl_cnt - chart_cnt
        max_page = max((c.page_number for c in chunks), default=1)

        doc_entry = {
            "id": cfg["doc_id"],
            "title": cfg["title"],
            "file_name": os.path.basename(fpath),
            "file_path": fpath,
            "file_size": fsize,
            "institution": cfg["institution"],
            "publish_date": cfg["publish_date"],
            "report_period": cfg["report_period"],
            "cognitive_layer": cfg["cognitive_layer"],
            "category_path": cfg["category_path"],
            "total_pages": max_page,
            "total_chunks": len(chunks),
            "total_tables": tbl_cnt,
            "total_charts": chart_cnt,
            "total_texts": text_cnt,
            "created_at": "2026-10-25 11:30:00"
        }
        docs_result.append(doc_entry)

        stock_pat = re.compile(r"(?<!\d)(?:00|30|60|68)\d{4}(?!\d)")
        fund_pat = re.compile(r"(?<!\d)(?:00|01|15|16|50|51)\d{4}(?!\d)")
        for idx, c in enumerate(chunks):
            cid = f"{cfg['prefix']}_{idx:03d}"
            stocks = c.metadata.get("stock_codes") or list(set(stock_pat.findall(c.content)))
            funds = c.metadata.get("fund_codes") or list(set(fund_pat.findall(c.content)))
            c_dict = {
                "id": cid,
                "chunk_id": cid,
                "document_id": cfg["doc_id"],
                "doc_title": cfg["title"],
                "chunk_index": idx,
                "page_number": c.page_number,
                "section_title": c.section_title or "正文段落",
                "content": c.content,
                "content_length": len(c.content),
                "cognitive_layer": cfg["cognitive_layer"],
                "category_path": cfg["category_path"],
                "stock_codes": stocks,
                "fund_codes": funds,
                "has_table": c.has_table,
                "has_chart": c.has_chart,
                "metadata": c.metadata or {},
                "publish_date": cfg["publish_date"],
                "institution": cfg["institution"],
                "created_at": "2026-10-25 11:30:00"
            }
            chunks_result.append(c_dict)

        print(f"   ✓ 成功切分: {len(chunks)} 个 Chunk (表格: {tbl_cnt}, 图表: {chart_cnt}, 纯文本: {text_cnt})")

    out_file = "data/parsed_documents_cache.json"
    payload = {
        "documents": docs_result,
        "chunks": chunks_result,
        "total_documents": len(docs_result),
        "total_chunks": len(chunks_result)
    }

    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    print("\n" + "=" * 70)
    print(f"🎉 处理完成！已成功将 {len(docs_result)} 份核心文档与 {len(chunks_result)} 个语义切片写入: {out_file}")
    print("=" * 70)


if __name__ == "__main__":
    run()
