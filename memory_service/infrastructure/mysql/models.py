from datetime import datetime, timezone
from sqlalchemy import (
    Column,
    String,
    Integer,
    Text,
    DateTime,
    JSON,
    ForeignKey,
    ForeignKeyConstraint,
    UniqueConstraint,
    Index,
    Float,
)
from sqlalchemy.orm import declarative_base, relationship

Base = declarative_base()


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class LtmMemoryModel(Base):
    """权威记忆身份与当前发布指针表"""
    __tablename__ = "ltm_memory"

    id = Column(String(36), primary_key=True)
    user_id = Column(String(64), nullable=False, index=True)
    memory_type = Column(String(24), nullable=False)  # FACT, PREFERENCE, PROCEDURE
    status = Column(String(24), nullable=False, default="DRAFT")  # DRAFT, ACTIVE, DELETED etc.
    published_version = Column(Integer, nullable=True)  # 当前已对外生效的发布版本指针
    created_at = Column(DateTime, default=utc_now, nullable=False)
    updated_at = Column(DateTime, default=utc_now, onupdate=utc_now, nullable=False)

    __table_args__ = (
        Index("idx_user_type_status", "user_id", "memory_type", "status"),
    )

    # 关联版本列表
    versions = relationship("LtmMemoryVersionModel", back_populates="memory", cascade="all, delete-orphan")


class LtmMemoryVersionModel(Base):
    """不可变版本表：存储具体的每一代快照"""
    __tablename__ = "ltm_memory_version"

    memory_id = Column(String(36), ForeignKey("ltm_memory.id", ondelete="CASCADE"), primary_key=True)
    version = Column(Integer, primary_key=True)
    content = Column(Text, nullable=False)
    data_json = Column(JSON, nullable=False)
    verification_status = Column(String(24), nullable=False, default="UNVERIFIED")
    valid_from = Column(DateTime, nullable=True)
    valid_to = Column(DateTime, nullable=True)
    created_at = Column(DateTime, default=utc_now, nullable=False)

    memory = relationship("LtmMemoryModel", back_populates="versions")
    procedure_detail = relationship("LtmProcedureModel", back_populates="version_ref", uselist=False, cascade="all, delete-orphan")
    pack = relationship("LtmMemoryPackModel", back_populates="version_ref", uselist=False, cascade="all, delete-orphan")
    evidences = relationship("LtmEvidenceModel", back_populates="version_ref", cascade="all, delete-orphan")


class LtmFactSlotModel(Base):
    """当前单值/多值事实槽位表：用于 L0 秒级精确定位"""
    __tablename__ = "ltm_fact_slot"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(String(64), nullable=False)
    fact_key = Column(String(128), nullable=False)  # 槽位 predicate
    memory_id = Column(String(36), nullable=False)
    current_version = Column(Integer, nullable=False)
    value_json = Column(JSON, nullable=False)
    updated_at = Column(DateTime, default=utc_now, onupdate=utc_now, nullable=False)

    __table_args__ = (
        UniqueConstraint("user_id", "fact_key", name="uk_user_fact_slot"),
        Index("idx_slot_user", "user_id"),
    )


class LtmProcedureModel(Base):
    """版本化过程性经验细节表"""
    __tablename__ = "ltm_procedure"

    memory_id = Column(String(36), primary_key=True)
    version = Column(Integer, primary_key=True)
    task_type = Column(String(64), nullable=False, index=True)
    trigger_conditions_json = Column(JSON, nullable=False)
    preconditions_json = Column(JSON, nullable=False)
    steps_json = Column(JSON, nullable=False)
    applicability_json = Column(JSON, nullable=False)
    validation_rules_json = Column(JSON, nullable=False)
    failure_signatures_json = Column(JSON, nullable=False)
    recovery_actions_json = Column(JSON, nullable=False)

    __table_args__ = (
        ForeignKeyConstraint(
            ["memory_id", "version"],
            ["ltm_memory_version.memory_id", "ltm_memory_version.version"],
            ondelete="CASCADE",
        ),
    )

    version_ref = relationship("LtmMemoryVersionModel", back_populates="procedure_detail")


class LtmEvidenceModel(Base):
    """记忆证据与来源引用表"""
    __tablename__ = "ltm_evidence"

    id = Column(String(36), primary_key=True)
    memory_id = Column(String(36), nullable=False)
    version = Column(Integer, nullable=False)
    evidence_type = Column(String(32), nullable=False)
    source_ref = Column(String(256), nullable=False)
    payload_json = Column(JSON, nullable=False)
    confidence = Column(Float, nullable=False, default=1.0)
    created_at = Column(DateTime, default=utc_now, nullable=False)

    __table_args__ = (
        ForeignKeyConstraint(
            ["memory_id", "version"],
            ["ltm_memory_version.memory_id", "ltm_memory_version.version"],
            ondelete="CASCADE",
        ),
        Index("idx_evidence_source", "source_ref"),
    )

    version_ref = relationship("LtmMemoryVersionModel", back_populates="evidences")


class LtmMemoryPackModel(Base):
    """预编译在线物化包表"""
    __tablename__ = "ltm_memory_pack"

    memory_id = Column(String(36), primary_key=True)
    version = Column(Integer, primary_key=True)
    compact_text = Column(Text, nullable=False)
    standard_text = Column(Text, nullable=False)
    retrieval_text = Column(Text, nullable=False)
    token_count = Column(Integer, nullable=False)

    __table_args__ = (
        ForeignKeyConstraint(
            ["memory_id", "version"],
            ["ltm_memory_version.memory_id", "ltm_memory_version.version"],
            ondelete="CASCADE",
        ),
    )

    version_ref = relationship("LtmMemoryVersionModel", back_populates="pack")


class LtmEpisodeModel(Base):
    """离线分析的单次交互/经历中间体表"""
    __tablename__ = "ltm_episode"

    id = Column(String(36), primary_key=True)
    user_id = Column(String(64), nullable=False, index=True)
    source_ref = Column(String(256), nullable=False, index=True)
    task_type = Column(String(64), nullable=True)
    summary = Column(Text, nullable=False)
    outcome = Column(String(32), nullable=False)
    details_json = Column(JSON, nullable=False)
    created_at = Column(DateTime, default=utc_now, nullable=False)


class LtmJobModel(Base):
    """离线构建与索引后台队列表"""
    __tablename__ = "ltm_job"

    id = Column(String(36), primary_key=True)
    job_type = Column(String(32), nullable=False)  # CONSOLIDATE, COMPILE, PUBLISH
    memory_id = Column(String(36), nullable=True)
    version = Column(Integer, nullable=True)
    status = Column(String(24), nullable=False, default="PENDING")  # PENDING, PROCESSING, COMPLETED, FAILED
    retry_count = Column(Integer, nullable=False, default=0)
    error_message = Column(Text, nullable=True)
    created_at = Column(DateTime, default=utc_now, nullable=False)
    updated_at = Column(DateTime, default=utc_now, onupdate=utc_now, nullable=False)

    __table_args__ = (
        Index("idx_job_status", "status", "created_at"),
    )
