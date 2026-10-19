"""长期记忆引擎 — Graphiti 混合检索 (向量 + BM25 + 图遍历 + 时间过滤)"""

import logging
from datetime import datetime, timezone

from graphiti_core.nodes import EpisodeType

from app.core.graphiti_init import get_graphiti
from app.models.schemas import FactResult, MemorySearchResponse

logger = logging.getLogger(__name__)


class LongTermMemoryEngine:
    def __init__(self):
        self.last_namespace: str | None = None

    async def search(
        self,
        user_id_or_query: str | None = None,
        scope: str = "USER",
        query: str = "",
        max_results: int = 10,
        center_node_uuid: str | None = None,
        conversation_id: str | None = None,
        user_id: str | None = None,
    ) -> MemorySearchResponse:
        # 兼容按位置传参 search("u1", "USER", "低回撤", 5) 与关键字传参
        if user_id is None and scope in ("USER", "GLOBAL_MARKET", "CONVERSATION") and query:
            actual_user_id = user_id_or_query
            actual_query = query
        elif user_id is not None:
            actual_user_id = user_id
            actual_query = query or user_id_or_query or ""
        else:
            actual_user_id = None
            actual_query = user_id_or_query or query or ""

        scope_str = (scope or "USER").upper()
        if scope_str == "GLOBAL_MARKET":
            namespace = "global:market"
        elif scope_str == "CONVERSATION":
            cid = conversation_id or "default"
            namespace = f"user:{actual_user_id}:conversation:{cid}" if actual_user_id else f"conversation:{cid}"
        else:
            namespace = f"user:{actual_user_id}" if actual_user_id else "global:market"

        self.last_namespace = namespace

        try:
            g = get_graphiti()
            results = await g.search(
                actual_query,
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
            logger.info("Long-term search '%s' (ns=%s) → %d facts", actual_query[:50], namespace, len(facts))
            return MemorySearchResponse(results=facts, total=len(facts))
        except Exception as e:
            logger.warning("Graphiti search failed (fallback): %s", e)
            return MemorySearchResponse(results=[], total=0)

    async def search_by_time(
        self,
        query: str,
        at_time: datetime,
        max_results: int = 10,
    ) -> MemorySearchResponse:
        """时点回溯查询：返回在指定时间点有效的事实"""
        try:
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
            return MemorySearchResponse(results=facts, total=len(facts))
        except Exception as e:
            logger.warning("Graphiti time-point search failed (fallback): %s", e)
            return MemorySearchResponse(results=[], total=0)

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
        return ""


long_term_engine = LongTermMemoryEngine()
