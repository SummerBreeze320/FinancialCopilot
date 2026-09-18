"""长期记忆检索 API"""

import logging
from datetime import datetime
from fastapi import APIRouter, Query

from app.engine.long_term import long_term_engine
from app.models.schemas import MemorySearchRequest, MemorySearchResponse

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/v1/memory", tags=["memory"])


@router.post("/search", response_model=MemorySearchResponse)
async def search_memory(body: MemorySearchRequest):
    """混合检索：向量 + BM25 + 图遍历"""
    return await long_term_engine.search(
        query=body.query,
        max_results=body.max_results,
        center_node_uuid=body.center_node_uuid,
    )


@router.post("/search/temporal", response_model=MemorySearchResponse)
async def search_memory_temporal(
    body: MemorySearchRequest,
    at_time: datetime = Query(..., description="ISO 8601 时间点"),
):
    """时点回溯查询：返回在指定时间点有效的事实"""
    return await long_term_engine.search_by_time(
        query=body.query,
        at_time=at_time,
        max_results=body.max_results,
    )


@router.post("/forget")
async def forget_memory(
    user_id: str | None = None,
    session_id: str | None = None,
    before: datetime | None = None,
):
    """遗忘指定记忆（按时间/用户/会话范围）"""
    from app.engine.short_term import short_term_engine

    cleared = 0
    if session_id:
        ok = await short_term_engine.clear(session_id)
        cleared += 1 if ok else 0

    return {
        "forgotten": cleared,
        "user_id": user_id,
        "session_id": session_id,
        "before": before.isoformat() if before else None,
    }
