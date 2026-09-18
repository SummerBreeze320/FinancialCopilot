import logging
from typing import Any
from memory_service.infrastructure.milvus.client import milvus_client

logger = logging.getLogger(__name__)


class VectorRepository:
    """Milvus 向量索引仓储：只管理可重建的派生检索索引"""

    def __init__(self):
        self.client_manager = milvus_client

    async def upsert_vector(
        self,
        memory_id: str,
        memory_version: int,
        user_id: str,
        memory_type: str,
        task_type: str,
        embedding: list[float],
    ) -> str:
        """
        版本化主键插入：vector_id = f"{memory_id}:{memory_version}"
        防止不同版本之间发生主键覆盖竞争
        """
        vector_id = f"{memory_id}:{memory_version}"
        collection = self.client_manager.get_collection()
        if not collection:
            logger.warning("Milvus collection not available, vector insertion skipped (offline mock mode).")
            return vector_id

        data = [
            [vector_id],
            [memory_id],
            [memory_version],
            [user_id],
            [memory_type],
            [task_type or "GENERAL"],
            [embedding],
        ]
        collection.insert(data)
        collection.flush()
        return vector_id

    async def search_vectors(
        self,
        user_id: str,
        query_vector: list[float],
        top_k: int = 5,
        memory_type: str | None = None,
        task_type: str | None = None,
    ) -> list[dict[str, Any]]:
        """
        根据用户、类型进行标量预过滤，并执行余弦相似度检索
        """
        collection = self.client_manager.get_collection()
        if not collection:
            return []

        expr_parts = [f'user_id == "{user_id}"']
        if memory_type:
            expr_parts.append(f'memory_type == "{memory_type}"')
        if task_type:
            expr_parts.append(f'task_type == "{task_type}"')
        expr = " and ".join(expr_parts)

        search_params = {"metric_type": "COSINE", "params": {"ef": 64}}
        results = collection.search(
            data=[query_vector],
            anns_field="embedding",
            param=search_params,
            limit=top_k,
            expr=expr,
            output_fields=["memory_id", "memory_version", "memory_type", "task_type"],
        )

        candidates = []
        for hits in results:
            for hit in hits:
                candidates.append({
                    "vector_id": hit.id,
                    "distance": hit.distance,
                    "memory_id": hit.entity.get("memory_id"),
                    "memory_version": hit.entity.get("memory_version"),
                    "memory_type": hit.entity.get("memory_type"),
                    "task_type": hit.entity.get("task_type"),
                })
        return candidates

    async def delete_by_memory_id(self, memory_id: str) -> None:
        """根据 memory_id 删除对应的全部版本向量"""
        collection = self.client_manager.get_collection()
        if not collection:
            return
        expr = f'memory_id == "{memory_id}"'
        collection.delete(expr)
