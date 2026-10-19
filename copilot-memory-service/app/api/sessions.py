"""会话与短期记忆 API — 严格用户所有权校验"""

import logging
from datetime import datetime, timezone
from fastapi import APIRouter, Depends, HTTPException

from app.api.security import verify_service_token
from app.engine.short_term import short_term_engine
from app.models.schemas import (
    MessageCreate,
    SessionResponse,
    ContextResponse,
)

logger = logging.getLogger(__name__)

router = APIRouter(tags=["sessions"])


# ── 用户级所有权隔离路由 ────────────────────────────────────

@router.post(
    "/v1/users/{user_id}/sessions/{conversation_id}/messages",
    dependencies=[Depends(verify_service_token)],
)
async def add_user_session_message(
    user_id: str,
    conversation_id: str,
    body: MessageCreate,
):
    """写入指定用户的对话消息到短期记忆"""
    result = await short_term_engine.add_message(
        user_id_or_session=user_id,
        conversation_id_or_user=conversation_id,
        role=body.role,
        content=body.content,
        metadata=body.metadata,
    )
    return result


@router.get(
    "/v1/users/{user_id}/sessions/{conversation_id}/context",
    response_model=ContextResponse,
    dependencies=[Depends(verify_service_token)],
)
async def get_user_session_context(
    user_id: str,
    conversation_id: str,
    max_messages: int | None = None,
):
    """读取用户会话短期记忆，跨用户访问严格返回 404"""
    messages = await short_term_engine.get_context(
        session_id_or_user_id=user_id,
        conversation_id=conversation_id,
        max_messages=max_messages,
    )
    if messages is None:
        raise HTTPException(status_code=404, detail="Session not found or belongs to another user")
    return ContextResponse(
        session_id=conversation_id,
        messages=messages,
        total=len(messages),
    )


@router.delete(
    "/v1/users/{user_id}/sessions/{conversation_id}",
    dependencies=[Depends(verify_service_token)],
)
async def clear_user_session(
    user_id: str,
    conversation_id: str,
):
    """清空用户所属会话的短期记忆"""
    deleted = await short_term_engine.clear(
        session_id_or_user_id=user_id,
        conversation_id=conversation_id,
    )
    if not deleted:
        raise HTTPException(status_code=404, detail="Session not found or unauthorized")
    return {"deleted": True, "session_id": conversation_id, "user_id": user_id}


# ── 兼容历史会话路由 ────────────────────────────────────────

@router.post(
    "/v1/sessions",
    response_model=SessionResponse,
    dependencies=[Depends(verify_service_token)],
)
async def create_session(user_id: str, session_id: str | None = None):
    """创建/确认会话"""
    import uuid
    sid = session_id or str(uuid.uuid4())
    return SessionResponse(
        session_id=sid,
        user_id=user_id,
        created_at=datetime.now(timezone.utc),
    )


@router.post(
    "/v1/sessions/{session_id}/messages",
    dependencies=[Depends(verify_service_token)],
)
async def add_message(session_id: str, body: MessageCreate, user_id: str = "default"):
    """写入一条对话消息"""
    result = await short_term_engine.add_message(
        user_id_or_session=session_id,
        conversation_id_or_user=None,
        role=body.role,
        content=body.content,
        metadata=body.metadata,
        session_id=session_id,
        user_id=user_id,
    )
    return result


@router.get(
    "/v1/sessions/{session_id}/context",
    response_model=ContextResponse,
    dependencies=[Depends(verify_service_token)],
)
async def get_context(session_id: str, max_messages: int | None = None):
    """读取短期记忆上下文"""
    messages = await short_term_engine.get_context(
        session_id_or_user_id=session_id,
        conversation_id=None,
        max_messages=max_messages,
    )
    if messages is None:
        messages = []
    return ContextResponse(
        session_id=session_id,
        messages=messages,
        total=len(messages),
    )


@router.delete(
    "/v1/sessions/{session_id}",
    dependencies=[Depends(verify_service_token)],
)
async def clear_session(session_id: str):
    """清空会话短期记忆"""
    deleted = await short_term_engine.clear(session_id_or_user_id=session_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Session not found")
    return {"deleted": True, "session_id": session_id}
