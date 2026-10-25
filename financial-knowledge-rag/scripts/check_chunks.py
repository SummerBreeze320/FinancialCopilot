import asyncio
import json
import asyncpg
import sys
sys.stdout.reconfigure(encoding='utf-8')

async def check():
    conn = await asyncpg.connect("postgresql://postgres:123456@127.0.0.1:15432/financial_copilot")
    docs = await conn.fetch("SELECT id, title, chunk_count FROM knowledge_documents ORDER BY publish_date DESC")
    for d in docs:
        doc_id = d["id"]
        rows = await conn.fetch("SELECT chunk_index, page_number, section_title, content, metadata FROM knowledge_chunks WHERE document_id=$1 ORDER BY chunk_index", doc_id)
        chart_chunks = []
        table_chunks = []
        for r in rows:
            meta = json.loads(r["metadata"]) if isinstance(r["metadata"], str) else (r["metadata"] or {})
            content = r["content"]
            is_chart = bool(meta.get("has_chart")) or ("走势图" in content) or ("折线图" in content)
            is_table = bool(meta.get("has_table")) or ("|" in content)
            if is_chart:
                chart_chunks.append(r)
            elif is_table:
                table_chunks.append(r)
        print(f"[{doc_id}] {d['title']}: Total={len(rows)}, Tables={len(table_chunks)}, Charts={len(chart_chunks)}, Texts={len(rows)-len(table_chunks)-len(chart_chunks)}")
    await conn.close()

if __name__ == "__main__":
    asyncio.run(check())
