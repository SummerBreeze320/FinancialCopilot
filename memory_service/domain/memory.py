from datetime import datetime, timezone
from typing import Any
from pydantic import BaseModel, Field
from memory_service.domain.enums import MemoryType, MemoryStatus, VerificationStatus
from memory_service.domain.fact import Fact
from memory_service.domain.procedure import Procedure
from memory_service.domain.memory_pack import MemoryPack
from memory_service.domain.evidence import Evidence


class MemoryVersion(BaseModel):
    """不可变的单版本记忆数据快照"""
    memory_id: str = Field(description="记忆主键ID")
    version: int = Field(ge=1, description="版本递增号 (1, 2, ...)")
    content: str = Field(description="人类可读的标准记忆描述")
    data_json: dict[str, Any] = Field(default_factory=dict, description="结构化详情（Fact或Procedure的完整载荷）")
    verification_status: VerificationStatus = Field(default=VerificationStatus.UNVERIFIED)
    valid_from: datetime | None = Field(default=None)
    valid_to: datetime | None = Field(default=None)
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

    # 关联物化与证据信息（内存聚合）
    pack: MemoryPack | None = None
    evidences: list[Evidence] = Field(default_factory=list)
    fact_detail: Fact | None = None
    procedure_detail: Procedure | None = None


class Memory(BaseModel):
    """
    记忆聚合根：持有唯一身份、用户归属、全局生命周期状态以及当前发布版本指针
    """
    id: str = Field(description="全局唯一UUID")
    user_id: str = Field(description="所属用户ID")
    memory_type: MemoryType = Field(description="记忆类型: FACT, PREFERENCE, PROCEDURE")
    status: MemoryStatus = Field(default=MemoryStatus.DRAFT, description="生命周期状态: DRAFT, ACTIVE, DELETED等")
    published_version: int | None = Field(default=None, description="当前对外生效的不可变版本号指针")
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

    # 包含的版本记录（按需装配）
    versions: list[MemoryVersion] = Field(default_factory=list)

    @property
    def is_active(self) -> bool:
        return self.status == MemoryStatus.ACTIVE and self.published_version is not None
