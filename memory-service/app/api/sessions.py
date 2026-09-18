"""会话与短期记忆 API"""

import logging
from fastapi import APIRouter, HTTPException

from app.engine.short_term import short_term_engine
from app.models.schemas import (
    MessageCreate,
    MessageRole,
    SessionResponse,
    ContextRequest,
    ContextResponse,
)

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/v1/sessions", tags=["sessions"])


@router.post("", response_model=SessionResponse)
async def create_session(user_id: str, session_id: str | None = None):
    """创建/确认会话（实际会话在第一条消息时自动创建）"""
    import uuid
    sid = session_id or str(uuid.uuid4())
    return SessionResponse(
        session_id=sid,
        user_id=user_id,
        created_at=__import__("datetime").datetime.now(__import__("datetime").timezone.utc),
    )


@router.post("/{session_id}/messages")
async def add_message(session_id: str, body: MessageCreate, user_id: str):
    """写入一条对话消息到短期记忆 + Graphiti Episode"""
    result = await short_term_engine.add_message(
        session_id=session_id,
        user_id=user_id,
        role=body.role,
        content=body.content,
        metadata=body.metadata,
    )
    return result


@router.get("/{session_id}/context", response_model=ContextResponse)
async def get_context(session_id: str, max_messages: int | None = None):
    """读取短期记忆上下文"""
    messages = await short_term_engine.get_context(session_id, max_messages)
    return ContextResponse(
        session_id=session_id,
        messages=messages,
        total=len(messages),
    )


@router.delete("/{session_id}")
async def clear_session(session_id: str):
    """清空会话短期记忆"""
    deleted = await short_term_engine.clear(session_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Session not found")
    return {"deleted": True, "session_id": session_id}
