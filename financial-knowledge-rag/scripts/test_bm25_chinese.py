"""验证中文分词与 BM25 检索效果的测试脚本。"""

import asyncio
from app.retrieval.pgvector_store import PgVectorStore


async def test_chinese_bm25():
    store = PgVectorStore()
    await store.init_pool()

    print("=== 1. 刷新数据库全部切片的中文分词倒排索引 (tsv_content) ===")
    count = await store.reindex_all_chunks_tsv()
    print(f"刷新成功，更新切片数: {count}")

    print("\n=== 2. 测试中文全文检索与 BM25Okapi 打分 ===")
    query = "诺安成长2026年第1季度和第2季度的基金份额总额分别是多少"
    results = await store.fulltext_search(query_text=query, top_k=5)

    print(f"查询语句: {query}")
    print(f"召回结果数量: {len(results)}")
    for i, r in enumerate(results):
        print(f"\n--- [Rank #{i+1}] Doc: {r.get('doc_title')}")
        print(f"    Section: {r.get('section_title')}")
        print(f"    Chunk Index: {r.get('chunk_index')}, Has Table: {r.get('has_table')}")
        print(f"    Text Score (BM25+Coverage): {r.get('text_score')}, BM25 Raw: {r.get('bm25_score')}")
        snippet = r.get("content", "")[:150].replace("\n", " ")
        print(f"    Snippet: {snippet}...")

    await store.close()


if __name__ == "__main__":
    asyncio.run(test_chinese_bm25())
