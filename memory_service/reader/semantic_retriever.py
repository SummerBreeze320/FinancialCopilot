from typing import Any
from memory_service.domain.enums import MemoryType
from memory_service.domain.memory import MemoryVersion
from memory_service.infrastructure.mysql.memory_repository import MemoryRepository
from memory_service.infrastructure.milvus.vector_repository import VectorRepository


class SemanticRetriever:
    """
    L1 语义向量检索器：
    基于 Milvus 进行标量前置过滤与向量近似最近邻 (ANN) 检索。
    确保召回的记忆与 MySQL 权威的 published_version 强校验一致。
    """

    def __init__(
        self,
        memory_repo: MemoryRepository,
        vector_repo: VectorRepository | None = None,
    ):
        self.repo = memory_repo
        self.vector_repo = vector_repo or VectorRepository()

    async def retrieve(
        self,
        user_id: str,
        query_embedding: list[float] | None = None,
        top_k: int = 5,
        memory_types: list[MemoryType] | None = None,
        task_type: str | None = None,
    ) -> list[tuple[MemoryVersion, float]]:
        """
        执行向量检索与版本校验
        """
        results: list[tuple[MemoryVersion, float]] = []

        if query_embedding:
            m_types = [t.value for t in memory_types] if memory_types else None
            hits = await self.vector_repo.search_vectors(
                query_vector=query_embedding,
                user_id=user_id,
                top_k=top_k,
                memory_types=m_types,
                task_type=task_type,
            )

            for hit in hits:
                mem_id = hit.get("memory_id")
                score = float(hit.get("score", 0.0))
                # 严格通过 MySQL 校验当前生效的发布版本，防止读取过时或未发布快照
                published_ver = await self.repo.get_published_version(mem_id)
                if published_ver and published_ver.pack:
                    results.append((published_ver, score))

        # 降级/补充：若未提供 query_embedding 但提供了明确的 task_type，直接检索该任务的过程性经验
        elif task_type:
            procs = await self.repo.list_active_memories_by_type(user_id, MemoryType.PROCEDURE)
            for m in procs:
                if m.published_version:
                    ver = await self.repo.get_version(m.id, m.published_version)
                    if ver and ver.procedure_detail and ver.procedure_detail.task_type == task_type:
                        results.append((ver, 1.0))

        return results
