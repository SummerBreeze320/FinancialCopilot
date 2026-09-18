"""长期记忆引擎 — Graphiti 混合检索 (向量 + BM25 + 图遍历 + 时间过滤)"""

import logging
from datetime import datetime, timezone

from graphiti_core.nodes import EpisodeType

from app.core.graphiti_init import get_graphiti
from app.models.schemas import FactResult, MemorySearchResponse

logger = logging.getLogger(__name__)


class LongTermMemoryEngine:
    async def search(
        self,
        query: str,
        max_results: int = 10,
        center_node_uuid: str | None = None,
    ) -> MemorySearchResponse:
        g = get_graphiti()
        results = await g.search(
            query,
            max_results=max_results,
            center_node_uuid=center_node_uuid,
        )

        facts = []
        for r in results:
            facts.append(FactResult(
                uuid=str(r.uuid),
                fact=r.fact,
                valid_at=getattr(r, "valid_at", None),
                invalid_at=getattr(r, "invalid_at", None),
                source_node_uuid=str(r.source_node_uuid) if hasattr(r, "source_node_uuid") else None,
                target_node_uuid=str(r.target_node_uuid) if hasattr(r, "target_node_uuid") else None,
            ))

        logger.info("Long-term search '%s' → %d facts", query[:50], len(facts))
        return MemorySearchResponse(results=facts, total=len(facts))

    async def search_by_time(
        self,
        query: str,
        at_time: datetime,
        max_results: int = 10,
    ) -> MemorySearchResponse:
        """时点回溯查询：返回在指定时间点有效的事实"""
        g = get_graphiti()
        results = await g.search(
            query,
            max_results=max_results,
        )

        facts = []
        for r in results:
            valid = getattr(r, "valid_at", None)
            invalid = getattr(r, "invalid_at", None)
            if valid and valid <= at_time:
                if invalid is None or invalid > at_time:
                    facts.append(FactResult(
                        uuid=str(r.uuid),
                        fact=r.fact,
                        valid_at=valid,
                        invalid_at=invalid,
                    ))

        logger.info("Time-point search '%s' @ %s → %d facts", query[:50], at_time, len(facts))
        return MemorySearchResponse(results=facts, total=len(facts))

    async def add_fact(
        self,
        subject: str,
        predicate: str,
        object_: str,
        reference_time: datetime | None = None,
    ) -> str:
        """直接写入事实三元组"""
        g = get_graphiti()
        ref_time = reference_time or datetime.now(timezone.utc)
        await g.add_episode(
            name=f"fact:{subject}:{predicate}:{datetime.now(timezone.utc).isoformat()}",
            episode_body=f"{subject} {predicate} {object_}",
            source=EpisodeType.text,
            source_description="direct fact injection",
            reference_time=ref_time,
        )
        return "ok"


long_term_engine = LongTermMemoryEngine()
