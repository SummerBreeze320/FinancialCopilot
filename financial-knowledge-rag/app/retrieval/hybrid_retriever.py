"""双路并发混合召回引擎 (Vector + BM25 Fulltext)。"""

import asyncio
import logging
from typing import Any, Dict, List, Optional, Tuple

from app.retrieval.embedding_client import EmbeddingClient
from app.retrieval.pgvector_store import PgVectorStore

logger = logging.getLogger(__name__)


class HybridRetriever:
    """结合向量语义与关键词全文的双路并发检索器。"""

    def __init__(
        self,
        store: PgVectorStore,
        embedding_client: EmbeddingClient,
    ) -> None:
        """初始化混合检索器。

        :param store: pgvector 数据库仓储接口
        :param embedding_client: 向量嵌入客户端
        """
        self.store = store
        self.embedding_client = embedding_client

    async def recall_candidates(
        self,
        query: str,
        filters: Optional[Dict[str, Any]] = None,
        top_vector: int = 30,
        top_fulltext: int = 30,
    ) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
        """并发执行稠密向量与稀疏全文召回，并合并为去重候选集。

        :param query: 用户检索提问
        :param filters: 结构化过滤字典
        :param top_vector: 向量粗召回候选深度 (默认 30)
        :param top_fulltext: 全文粗召回候选深度 (默认 30)
        :return: (去重后的候选切片列表, 召回漏斗指标字典)
        """
        # 1. 异步生成查询词向量
        query_emb = await self.embedding_client.embed_query(query)

        # 2. 并发执行向量检索与全文检索
        vector_task = self.store.vector_search(
            query_embedding=query_emb,
            top_k=top_vector,
            filters=filters,
        )
        fulltext_task = self.store.fulltext_search(
            query_text=query,
            top_k=top_fulltext,
            filters=filters,
        )

        vector_results, fulltext_results = await asyncio.gather(
            vector_task,
            fulltext_task,
            return_exceptions=False,
        )

        # 3. 候选集并集去重与位次记录
        candidates_map: Dict[str, Dict[str, Any]] = {}

        for rank, item in enumerate(vector_results, start=1):
            c_id = item["id"]
            cand = dict(item)
            cand["recall_sources"] = ["VECTOR"]
            cand["vector_rank"] = rank
            cand["fulltext_rank"] = None
            candidates_map[c_id] = cand

        for rank, item in enumerate(fulltext_results, start=1):
            c_id = item["id"]
            if c_id in candidates_map:
                existing = candidates_map[c_id]
                if "FULLTEXT" not in existing["recall_sources"]:
                    existing["recall_sources"].append("FULLTEXT")
                existing["fulltext_rank"] = rank
                # 保留全文检索分数值
                existing["text_score"] = item.get("text_score")
            else:
                cand = dict(item)
                cand["recall_sources"] = ["FULLTEXT"]
                cand["vector_rank"] = None
                cand["fulltext_rank"] = rank
                candidates_map[c_id] = cand

        deduped_candidates = list(candidates_map.values())

        funnel = {
            "vector_total_recalled": len(vector_results),
            "fulltext_total_recalled": len(fulltext_results),
            "union_candidates": len(deduped_candidates),
        }

        return deduped_candidates, funnel
