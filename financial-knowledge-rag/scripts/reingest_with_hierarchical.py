"""使用全新的大纲层级与块元素感知解析器重新入库 PDF 文档。"""

import asyncio
import sys
sys.path.insert(0, ".")
sys.stdout.reconfigure(encoding='utf-8')
from app.ingest.pdf_parser import PdfParser
from app.retrieval.embedding_client import EmbeddingClient
from app.retrieval.pgvector_store import PgVectorStore

pdf_path = r"D:\Users\longlong\Downloads\诺安成长混合型证券投资基金2026 年第 2 季度报告2026 年 06 月 30 日.pdf"

async def main():
    print("=" * 60)
    print("🚀 开始使用高级结构化大纲切块引擎处理 PDF...")
    print("=" * 60)

    parser = PdfParser()
    base_meta = {
        "doc_id": "doc_4102085",
        "doc_title": "诺安成长混合型证券投资基金2026年第2季度报告",
        "institution": "诺安基金管理有限公司",
        "publish_date": "2026-07-21",
        "cognitive_layer": "GROUND_TRUTH",
        "category_path": "GROUND_TRUTH.PERIODIC.FUND_PERIODIC",
    }

    chunks = parser.parse_file(pdf_path, metadata=base_meta)
    print(f"✅ 成功生成高质量语义切片: {len(chunks)} 个\n")

    for idx, c in enumerate(chunks, 1):
        tbl_flag = "[含表格]" if c.has_table else ("[含走势图]" if c.has_chart else "[纯文本]")
        print(f"  [{idx:02d}] P.{c.page_number:02d} {tbl_flag:7s} {c.section_title} (长度: {len(c.content)}字)")
        if c.metadata.get("stock_codes"):
            print(f"       -> 关联股票代码: {c.metadata.get('stock_codes')}")

    # 连接数据库，清空旧切片并写入新切片与向量
    store = PgVectorStore()
    await store.init_pool()

    print("\n[1/3] 正在清理该文档的旧切片...")
    async with store.pool.acquire() as conn:
        await conn.execute("DELETE FROM knowledge_chunks WHERE document_id = $1;", "doc_4102085")

    print("[2/3] 正在调用本地 Ollama 生成 1024 维 Embedding 向量...")
    embed_client = EmbeddingClient()
    texts = [c.content for c in chunks]
    embeddings = await embed_client.embed_texts(texts)
    print(f"      成功生成 {len(embeddings)} 条 1024 维向量！")

    print("[3/3] 正在批量写入 PostgreSQL 16 pgvector...")
    chunk_dicts = []
    for idx, c in enumerate(chunks):
        c_meta = dict(c.metadata)
        c_meta["has_table"] = c.has_table
        c_meta["has_chart"] = c.has_chart
        c_meta["section_title"] = c.section_title

        chunk_dicts.append({
            "id": f"chunk_noan_{idx:03d}",
            "document_id": "doc_4102085",
            "chunk_index": idx,
            "page_number": c.page_number,
            "section_title": c.section_title,
            "content": c.content,
            "cognitive_layer": "GROUND_TRUTH",
            "category_path": "GROUND_TRUTH.PERIODIC.FUND_PERIODIC",
            "stock_codes": c.metadata.get("stock_codes", []),
            "fund_codes": c.metadata.get("fund_codes", []),
            "metadata": c_meta,
        })

    await store.save_chunks(chunk_dicts, embeddings)

    # 更新主文档状态
    async with store.pool.acquire() as conn:
        await conn.execute(
            "UPDATE knowledge_documents SET chunk_count = $1, status = 'READY' WHERE id = $2;",
            len(chunk_dicts),
            "doc_4102085",
        )

    print("\n🎉 入库完成！所有切片已按大纲标题与完整无损表格建立 HNSW 向量索引！")
    await store.close()

if __name__ == "__main__":
    asyncio.run(main())
