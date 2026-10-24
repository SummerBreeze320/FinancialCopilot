"""Pydantic 请求/响应模型 — 记忆四分类 + 显式写入 + 多因子检索 + 清理整理"""

from datetime import datetime
from enum import Enum
from pydantic import BaseModel, Field


class MessageRole(str, Enum):
    USER = "user"
    ASSISTANT = "assistant"
    SYSTEM = "system"


class MemoryCategory(str, Enum):
    USER_PROFILE = "USER_PROFILE"          # 用户画像（风险偏好、投资经验、偏好行业/板块等）
    USER_FEEDBACK = "USER_FEEDBACK"        # 用户反馈/规矩约束（否定/肯定偏好、格式要求、投资原则）
    TASK_EPISODIC = "TASK_EPISODIC"        # 任务决策情节（情境事实、决策理由、买卖/调仓记录）
    RESOURCE_REFERENCE = "RESOURCE_REFERENCE"  # 外部资源引用（标的代码、研报观点、指标数据）


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


class FactResult(BaseModel):
    uuid: str
    fact: str
    category: str = MemoryCategory.USER_PROFILE.value
    importance: float = 0.5
    scenario: str | None = None
    reason: str | None = None
    valid_at: datetime | None = None
    invalid_at: datetime | None = None
    last_accessed_at: datetime | None = None
    access_count: int = 0
    score: float | None = None
    similarity: float | None = None
    recency_score: float | None = None
    importance_score: float | None = None
    source_node_uuid: str | None = None
    target_node_uuid: str | None = None
    metadata: dict | None = None


class MemorySearchRequest(BaseModel):
    query: str
    user_id: str | None = None
    session_id: str | None = None
    conversation_id: str | None = None
    scope: str = "USER"
    category: str | None = None
    max_results: int = Field(default=10, ge=1, le=50)
    center_node_uuid: str | None = None
    w_semantic: float = Field(default=0.5, ge=0.0, le=1.0)
    w_recency: float = Field(default=0.3, ge=0.0, le=1.0)
    w_importance: float = Field(default=0.2, ge=0.0, le=1.0)
    half_life_days: float = Field(default=30.0, gt=0.0)
    include_invalid: bool = False


class MemorySearchResponse(BaseModel):
    results: list[FactResult]
    total: int


class ExplicitMemoryCreate(BaseModel):
    user_id: str
    content: str
    category: str | None = None
    scenario: str | None = None
    reason: str | None = None
    importance: float = Field(default=0.8, ge=0.0, le=1.0)
    session_id: str | None = None
    reference_time: datetime | None = None


class ExplicitMemoryResponse(BaseModel):
    uuid: str
    fact: str
    category: str
    importance: float
    valid_at: datetime
    invalidated_uuids: list[str] = []


class MemoryConsolidateRequest(BaseModel):
    user_id: str
    category: str | None = None


class MemoryConsolidateResponse(BaseModel):
    user_id: str
    processed_count: int
    merged_count: int
    deduplicated_count: int


class MemoryPruneRequest(BaseModel):
    user_id: str | None = None
    max_idle_days: int = Field(default=90, ge=1)
    min_importance: float = Field(default=0.3, ge=0.0, le=1.0)
    prune_invalidated: bool = True


class MemoryPruneResponse(BaseModel):
    pruned_count: int
    pruned_invalidated: int
    pruned_cold: int
    pruned_low_importance: int


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
    category: str | None = None
    query: str | None = None
    before: datetime | None = None


class ContextRequest(BaseModel):
    session_id: str
    max_messages: int | None = None


class ContextResponse(BaseModel):
    session_id: str
    messages: list[dict]
    total: int
