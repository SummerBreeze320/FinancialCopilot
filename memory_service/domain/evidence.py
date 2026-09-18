import uuid
from datetime import datetime, timezone
from typing import Any
from pydantic import BaseModel, Field
from memory_service.domain.enums import EvidenceType


class Evidence(BaseModel):
    id: str = Field(default_factory=lambda: str(uuid.uuid4()), description="证据唯一标识")
    memory_id: str = Field(description="归属记忆ID")
    version: int = Field(default=1, description="归属记忆版本")
    evidence_type: EvidenceType = Field(description="证据类型")
    source_ref: str = Field(description="来源引用（如会话ID/消息ID/工具审计ID）")
    payload: dict[str, Any] = Field(default_factory=dict, description="证据具体内容快照")
    confidence: float = Field(default=1.0, ge=0.0, le=1.0, description="置信度")
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
