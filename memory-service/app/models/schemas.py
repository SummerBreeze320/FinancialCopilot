"""Pydantic 请求/响应模型"""

from datetime import datetime
from enum import Enum
from pydantic import BaseModel, Field


class MessageRole(str, Enum):
    USER = "user"
    ASSISTANT = "assistant"
    SYSTEM = "system"


class MessageCreate(BaseModel):
    role: MessageRole
    content: str
    metadata: dict | None = None


class SessionCreate(BaseModel):
    user_id: str
    session_id: str | None = None
    title: str | None = None


class SessionResponse(BaseModel):
    session_id: str
    user_id: str
    created_at: datetime


class MemorySearchRequest(BaseModel):
    query: str
    user_id: str | None = None
    session_id: str | None = None
    max_results: int = Field(default=10, ge=1, le=50)
    center_node_uuid: str | None = None


class FactResult(BaseModel):
    uuid: str
    fact: str
    valid_at: datetime | None = None
    invalid_at: datetime | None = None
    source_node_uuid: str | None = None
    target_node_uuid: str | None = None


class MemorySearchResponse(BaseModel):
    results: list[FactResult]
    total: int


class ProfileDimension(str, Enum):
    RISK_PREFERENCE = "risk_preference"
    EXPERTISE_LEVEL = "expertise_level"
    FOCUS_SECTORS = "focus_sectors"
    HOLDING_STYLE = "holding_style"
    FUND_PREFERENCE = "fund_preference"
    DECISION_STYLE = "decision_style"


class ProfileEntry(BaseModel):
    dimension: ProfileDimension
    value: str
    confidence: float = Field(ge=0.0, le=1.0)
    updated_at: datetime
    previous_value: str | None = None


class UserProfile(BaseModel):
    user_id: str
    dimensions: dict[str, ProfileEntry]
    last_active: datetime | None = None


class ProfileDiffEntry(BaseModel):
    dimension: ProfileDimension
    old_value: str
    new_value: str
    changed_at: datetime


class ProfileDiffResponse(BaseModel):
    user_id: str
    diffs: list[ProfileDiffEntry]


class ForgetRequest(BaseModel):
    user_id: str | None = None
    session_id: str | None = None
    fact_uuid: str | None = None
    before: datetime | None = None


class ContextRequest(BaseModel):
    session_id: str
    max_messages: int | None = None


class ContextResponse(BaseModel):
    session_id: str
    messages: list[dict]
    total: int
