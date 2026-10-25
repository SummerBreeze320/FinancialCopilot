import asyncio
import sys

sys.path.insert(0, ".")
sys.stdout.reconfigure(encoding="utf-8")

from app.retrieval.pgvector_store import PgVectorStore


async def check():
    s = PgVectorStore()
    try:
        await s.init_pool()
        docs = await s.list_documents()
        print("✓ 数据库连接成功！PostgreSQL 16 pgvector (Port: 15432)")
        print(f"📊 当前数据库中已持久化文档数: {len(docs)}")
        for d in docs:
            print(f"   • [{d.get('id')}] {d.get('title')} (发布日期: {d.get('publish_date')}, 切片: {d.get('chunk_count')})")
        
        async with s.pool.acquire() as conn:
            chunk_cnt = await conn.fetchval("SELECT count(*) FROM knowledge_chunks;")
            print(f"📦 当前切片总数 (knowledge_chunks): {chunk_cnt}")
    except Exception as e:
        print(f"❌ 数据库连接异常: {e}")
    finally:
        await s.close()


if __name__ == "__main__":
    asyncio.run(check())
