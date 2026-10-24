"""长期记忆检索与管理 API — 4分类检索、显式写入、冲突处理、定期整理与遗忘修剪"""

import logging
from datetime import datetime
from fastapi import APIRouter, Depends, Query

from app.api.security import verify_service_token
from app.engine.long_term import long_term_engine
from app.models.schemas import (
    MemorySearchRequest,
    MemorySearchResponse,
    ExplicitMemoryCreate,
    ExplicitMemoryResponse,
    MemoryConsolidateRequest,
    MemoryConsolidateResponse,
    MemoryPruneRequest,
    MemoryPruneResponse,
    ForgetRequest,
)

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/v1/memory", tags=["memory"])


@router.post(
    "/search",
    response_model=MemorySearchResponse,
    dependencies=[Depends(verify_service_token)],
)
async def search_memory(body: MemorySearchRequest):
    """多因子加权检索长期记忆 (支持四分类、时间半衰期衰减、重要性加权)"""
    return await long_term_engine.search(
        user_id_or_query=body.query,
        user_id=body.user_id,
        conversation_id=body.conversation_id,
        scope=body.scope,
        query=body.query,
        category=body.category,
        max_results=body.max_results,
        center_node_uuid=body.center_node_uuid,
        w_semantic=body.w_semantic,
        w_recency=body.w_recency,
        w_importance=body.w_importance,
        half_life_days=body.half_life_days,
        include_invalid=body.include_invalid,
    )


@router.post(
    "/search/temporal",
    response_model=MemorySearchResponse,
    dependencies=[Depends(verify_service_token)],
)
async def search_memory_temporal(
    body: MemorySearchRequest,
    at_time: datetime = Query(..., description="ISO 8601 时点"),
):
    """时点回溯查询：返回在指定时间点有效的事实"""
    return await long_term_engine.search_by_time(
        query=body.query,
        at_time=at_time,
        max_results=body.max_results,
        user_id=body.user_id,
    )


@router.post(
    "/explicit",
    response_model=ExplicitMemoryResponse,
    dependencies=[Depends(verify_service_token)],
)
async def create_explicit_memory(body: ExplicitMemoryCreate):
    """显式记忆即刻落库 (自动识别分类与冲突，冲突旧记忆标记失效 invalid_at)"""
    return await long_term_engine.add_explicit_memory(
        user_id=body.user_id,
        content=body.content,
        category=body.category,
        scenario=body.scenario,
        reason=body.reason,
        importance=body.importance,
        session_id=body.session_id,
        reference_time=body.reference_time,
    )


@router.post(
    "/consolidate",
    response_model=MemoryConsolidateResponse,
    dependencies=[Depends(verify_service_token)],
)
async def consolidate_memory(body: MemoryConsolidateRequest):
    """后台定期整理：扫描、归并同类型记忆、去重提炼"""
    return await long_term_engine.consolidate(
        user_id=body.user_id,
        category=body.category,
    )


@router.post(
    "/prune",
    response_model=MemoryPruneResponse,
    dependencies=[Depends(verify_service_token)],
)
async def prune_memory(body: MemoryPruneRequest):
    """清理遗忘机制：清理冷记忆、推翻失效记忆与低价值记忆"""
    return await long_term_engine.prune(
        user_id=body.user_id,
        max_idle_days=body.max_idle_days,
        min_importance=body.min_importance,
        prune_invalidated=body.prune_invalidated,
    )


@router.post("/forget", dependencies=[Depends(verify_service_token)])
async def forget_memory(body: ForgetRequest | None = None):
    """遗忘指定记忆（支持按 fact_uuid、user_id、session_id、query、category）"""
    from app.engine.short_term import short_term_engine

    cleared_short = 0
    cleared_long = 0

    if body:
        if body.session_id:
            ok = await short_term_engine.clear(body.session_id)
            cleared_short += 1 if ok else 0

        cleared_long = await long_term_engine.forget(
            user_id=body.user_id,
            fact_uuid=body.fact_uuid,
            category=body.category,
            query=body.query,
        )

    return {
        "short_term_cleared": cleared_short,
        "long_term_cleared": cleared_long,
        "request": body.model_dump() if body else {},
    }
