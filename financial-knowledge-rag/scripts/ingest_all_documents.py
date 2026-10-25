"""全量入库诺安成长相关全部 4 份核心投研文档。

涵盖：
1. 诺安成长混合型证券投资基金2026年第2季度报告 (2026-07-21, 48 切片, 12 表格, 2 走势图)
2. 诺安成长混合型证券投资基金2026年第1季度报告 (2026-04-22, 47 切片, 12 表格, 2 走势图)
3. 诺安基金管理有限公司关于旗下基金持有的股票估值调整的公告 (2026-06-30, 拓荆科技 688072 估值调整)
4. 诺安成长混合型证券投资基金基金产品资料概要更新 (2026-07-27, 费率/投资策略/产品概况)
"""

import asyncio
import datetime
import json
import os
import sys

sys.path.insert(0, ".")
sys.stdout.reconfigure(encoding="utf-8")

from app.ingest.pdf_parser import PdfParser
from app.retrieval.embedding_client import EmbeddingClient
from app.retrieval.pgvector_store import PgVectorStore

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
        "is_latest_period": True,
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
        "is_latest_period": False,
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
        "is_latest_period": False,
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
        "is_latest_period": True,
        "prefix": "chunk_kfs"
    }
]


async def main():
    print("=" * 70)
    print("🚀 开始全量多文档入库处理 (共 4 份核心投研文档)...")
    print("=" * 70)

    parser = PdfParser()
    embed_client = EmbeddingClient()
    store = PgVectorStore()
    await store.init_pool()

    total_all_chunks = 0

    for cfg in DOC_CONFIGS:
        doc_id = cfg["doc_id"]
        title = cfg["title"]
        file_path = cfg["file_path"]

        print(f"\n📄 处理文档: 【{title}】 (ID: {doc_id})")
        if not os.path.exists(file_path):
            print(f"⚠️ 文件不存在，跳过: {file_path}")
            continue

        # 1. 结构化大纲切块
        base_meta = {
            "doc_id": doc_id,
            "doc_title": title,
            "institution": cfg["institution"],
            "publish_date": cfg["publish_date"],
            "cognitive_layer": cfg["cognitive_layer"],
            "category_path": cfg["category_path"]
        }
        chunks = parser.parse_file(file_path, metadata=base_meta)
        table_count = sum(1 for c in chunks if c.has_table)
        chart_count = sum(1 for c in chunks if c.has_chart)
        text_count = len(chunks) - table_count - chart_count
        print(f"   ✓ 生成语义切块: {len(chunks)} 个 (表格: {table_count}, 走势图: {chart_count}, 纯文本: {text_count})")

        # 2. 清理旧切片并 upsert 主文档记录
        async with store.pool.acquire() as conn:
            await conn.execute("DELETE FROM knowledge_chunks WHERE document_id = $1;", doc_id)
            await conn.execute("""
                INSERT INTO knowledge_documents (
                    id, title, cognitive_layer, category_path, institution,
                    publish_date, report_period, is_latest_period, file_path,
                    chunk_count, status, updated_at
                ) VALUES ($1, $2, $3, $4, $5, $6::date, $7, $8, $9, $10, 'PROCESSING', NOW())
                ON CONFLICT (id) DO UPDATE SET
                    title = EXCLUDED.title,
                    cognitive_layer = EXCLUDED.cognitive_layer,
                    category_path = EXCLUDED.category_path,
                    institution = EXCLUDED.institution,
                    publish_date = EXCLUDED.publish_date,
                    report_period = EXCLUDED.report_period,
                    is_latest_period = EXCLUDED.is_latest_period,
                    file_path = EXCLUDED.file_path,
                    chunk_count = EXCLUDED.chunk_count,
                    status = 'PROCESSING',
                    updated_at = NOW();
            """, doc_id, title, cfg["cognitive_layer"], cfg["category_path"], cfg["institution"],
               datetime.date.fromisoformat(cfg["publish_date"]), cfg["report_period"], cfg["is_latest_period"], file_path, len(chunks))

        # 3. 批量生成 1024 维 Embedding
        print(f"   ⏳ 正在调用 Ollama 生成 {len(chunks)} 条 1024 维高维向量...")
        texts = [c.content for c in chunks]
        embeddings = await embed_client.embed_texts(texts)
        print(f"   ✓ 向量化完毕！")

        # 4. 构建 Chunk 实体并保存
        chunk_dicts = []
        for idx, c in enumerate(chunks):
            c_meta = dict(c.metadata)
            c_meta["has_table"] = c.has_table
            c_meta["has_chart"] = c.has_chart
            c_meta["section_title"] = c.section_title

            # 特殊标注股票代码（如估值调整公告涉及 688072 拓荆科技）
            stock_codes = list(c.metadata.get("stock_codes", []))
            if "拓荆科技" in c.content or "688072" in c.content:
                if "688072" not in stock_codes:
                    stock_codes.append("688072")
            c_meta["stock_codes"] = stock_codes

            chunk_dicts.append({
                "id": f"{cfg['prefix']}_{idx:03d}",
                "document_id": doc_id,
                "chunk_index": idx,
                "page_number": c.page_number,
                "section_title": c.section_title,
                "content": c.content,
                "cognitive_layer": cfg["cognitive_layer"],
                "category_path": cfg["category_path"],
                "stock_codes": stock_codes,
                "fund_codes": c.metadata.get("fund_codes", []),
                "metadata": c_meta,
            })

        await store.save_chunks(chunk_dicts, embeddings)

        # 5. 更新主文档状态为 READY
        async with store.pool.acquire() as conn:
            await conn.execute(
                "UPDATE knowledge_documents SET chunk_count = $1, status = 'READY', updated_at = NOW() WHERE id = $2;",
                len(chunk_dicts), doc_id
            )

        print(f"   ✅ 文档【{title}】入库成功！(切片数: {len(chunk_dicts)})")
        total_all_chunks += len(chunk_dicts)

    print("\n" + "=" * 70)
    print(f"🎉 全部 4 份投研文档入库完毕！总计持久化 {total_all_chunks} 个切片！")
    print("=" * 70)
    await store.close()


if __name__ == "__main__":
    asyncio.run(main())
