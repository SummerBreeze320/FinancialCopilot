"""将本地已解析的 5 份核心文档与 261 个切片同步持久化至 PostgreSQL 16 (15432)。"""

import asyncio
import hashlib
import json
import os
import sys
from datetime import date

sys.path.insert(0, ".")
sys.stdout.reconfigure(encoding="utf-8")

from app.retrieval.pgvector_store import PgVectorStore


def generate_pseudo_embedding(text: str, dim: int = 1024) -> list[float]:
    """根据文本内容确定性生成归一化高维向量 (避免无 Ollama 时报错)。"""
    h = hashlib.sha256(text.encode("utf-8")).digest()
    vals = []
    for i in range(dim):
        byte_val = h[i % len(h)]
        vals.append((float(byte_val) / 255.0) - 0.5)
    # L2 归一化
    norm = sum(v * v for v in vals) ** 0.5 or 1.0
    return [round(v / norm, 6) for v in vals]


async def sync():
    cache_path = os.path.join("data", "parsed_documents_cache.json")
    if not os.path.exists(cache_path):
        print(f"❌ 找不到缓存文件: {cache_path}")
        return

    with open(cache_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    docs = data.get("documents", [])
    chunks = data.get("chunks", [])

    print(f"🚀 开始同步 {len(docs)} 份核心文档与 {len(chunks)} 个切片至 PostgreSQL...")
    store = PgVectorStore()
    await store.init_pool()

    async with store.pool.acquire() as conn:
        # 1. 写入知识文档表
        for d in docs:
            pub_date = date.fromisoformat(d["publish_date"]) if d.get("publish_date") else date.today()
            await conn.execute("""
                INSERT INTO knowledge_documents (
                    id, title, cognitive_layer, category_path, institution,
                    publish_date, report_period, is_latest_period, file_path,
                    chunk_count, status, updated_at
                ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, 'READY', NOW())
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
                    status = 'READY',
                    updated_at = NOW();
            """, d["id"], d["title"], d.get("cognitive_layer", "GROUND_TRUTH"),
               d.get("category_path", "GROUND_TRUTH.PERIODIC"), d.get("institution", "诺安基金管理有限公司"),
               pub_date, d.get("report_period", "2026"), True, d.get("file_path", ""),
               d.get("total_chunks", 0))

        # 2. 清理旧切片并批量写入切片表
        for d in docs:
            await conn.execute("DELETE FROM knowledge_chunks WHERE document_id = $1;", d["id"])

        insert_chunk_sql = """
            INSERT INTO knowledge_chunks (
                id, document_id, chunk_index, page_number, section_title, content,
                content_length, embedding, cognitive_layer, category_path,
                stock_codes, fund_codes, metadata, tsv_content
            ) VALUES (
                $1, $2, $3, $4, $5, $6, $7, $8::vector, $9, $10, $11, $12, $13::jsonb,
                to_tsvector('simple', $14)
            ) ON CONFLICT (id) DO UPDATE SET
                content = EXCLUDED.content,
                content_length = EXCLUDED.content_length,
                embedding = EXCLUDED.embedding,
                section_title = EXCLUDED.section_title,
                page_number = EXCLUDED.page_number,
                metadata = EXCLUDED.metadata,
                tsv_content = EXCLUDED.tsv_content;
        """

        import jieba
        for c in chunks:
            emb = generate_pseudo_embedding(c["content"], 1024)
            emb_str = f"[{','.join(f'{x:.6f}' for x in emb)}]"
            meta_json = json.dumps(c.get("metadata", {}), ensure_ascii=False)
            sec = c.get("section_title") or "正文"
            cnt = c["content"]
            seg_text = " ".join([w.strip() for w in jieba.cut(f"{sec} {sec} {cnt}") if w.strip()])
            await conn.execute(
                insert_chunk_sql,
                c["id"],
                c["document_id"],
                c["chunk_index"],
                c.get("page_number", 1),
                sec,
                cnt,
                len(cnt),
                emb_str,
                c.get("cognitive_layer", "GROUND_TRUTH"),
                c.get("category_path", "GROUND_TRUTH.PERIODIC"),
                c.get("stock_codes", []),
                c.get("fund_codes", []),
                meta_json,
                seg_text,
            )

        total_in_db = await conn.fetchval("SELECT count(*) FROM knowledge_chunks;")
        doc_cnt = await conn.fetchval("SELECT count(*) FROM knowledge_documents;")
        print(f"✅ 数据库同步完毕！PostgreSQL 中现有 {doc_cnt} 份文档，{total_in_db} 个切片。")

    await store.close()


if __name__ == "__main__":
    asyncio.run(sync())
