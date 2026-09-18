from datetime import datetime, timezone
from typing import Any
from pydantic import BaseModel, Field


class Episode(BaseModel):
    """离线分析的中间产物，包含一次完整交互经历的摘要与执行结果，不必默认作为可召回记忆"""
    id: str = Field(description="经历ID")
    user_id: str = Field(description="用户ID")
    source_ref: str = Field(description="会话或批次ID")
    task_type: str | None = Field(default=None, description="任务类型")
    summary: str = Field(description="经历摘要")
    outcome: str = Field(description="执行结果: SUCCESS / FAILURE / PARTIAL")
    details_json: dict[str, Any] = Field(default_factory=dict, description="执行轨迹与工具反馈细节")
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
