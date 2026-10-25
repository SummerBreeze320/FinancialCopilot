import asyncio
import sys
sys.path.insert(0, ".")
from app.retrieval.pgvector_store import PgVectorStore

async def main():
    store = PgVectorStore()
    await store.init_pool()
    async with store.pool.acquire() as conn:
        for cid in ["chunk_q2_030", "chunk_q1_030"]:
            row = await conn.fetchrow("SELECT * FROM knowledge_chunks WHERE id = $1", cid)
            if row:
                chunk = store._format_chunk_row(row)
                print(f"=== {cid} (Table={chunk.get('has_table')}) ===")
                print("Title:", chunk.get("section_title"))
                print("Content:\n", chunk.get("content")[:300])
    await store.close()

if __name__ == "__main__":
    asyncio.run(main())
