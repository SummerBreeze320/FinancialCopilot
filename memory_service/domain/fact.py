from datetime import datetime, timezone
from typing import Any
from pydantic import BaseModel, Field
from memory_service.domain.enums import AssertionType


class Fact(BaseModel):
    """原子事实模型：S-P-V 三元组与有效性声明"""
    subject: str = Field(description="事实主体，通常为 user_id 或实体名")
    predicate: str = Field(description="事实谓词/槽位键，如 preferred_report_language, risk_level")
    value: Any = Field(description="事实值")
    assertion_type: AssertionType = Field(default=AssertionType.EXPLICIT, description="声明类型: 显式声明 / 隐式推断")
    valid_from: datetime | None = Field(default=None, description="时效起始时间")
    valid_to: datetime | None = Field(default=None, description="时效截止时间")


class FactSlot(BaseModel):
    """当前有效单值事实槽位"""
    user_id: str = Field(description="用户ID")
    fact_key: str = Field(description="槽位键 (即 predicate)")
    memory_id: str = Field(description="指向的主记忆ID")
    current_version: int = Field(description="当前有效版本号")
    value: Any = Field(description="当前缓存的值快照")
    updated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
