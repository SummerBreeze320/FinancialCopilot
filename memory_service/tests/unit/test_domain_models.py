import pytest
from datetime import datetime
from pydantic import ValidationError
from memory_service.domain.enums import (
    MemoryType,
    MemoryStatus,
    VerificationStatus,
    AssertionType,
    EvidenceType,
)
from memory_service.domain.memory import Memory, MemoryVersion
from memory_service.domain.fact import Fact, FactSlot
from memory_service.domain.procedure import Procedure
from memory_service.domain.memory_pack import MemoryPack
from memory_service.domain.evidence import Evidence


def test_memory_creation_and_defaults():
    mem = Memory(
        id="mem_001",
        user_id="user_123",
        memory_type=MemoryType.PREFERENCE,
    )
    assert mem.id == "mem_001"
    assert mem.user_id == "user_123"
    assert mem.memory_type == MemoryType.PREFERENCE
    assert mem.status == MemoryStatus.DRAFT
    assert mem.published_version is None
    assert not mem.is_active


def test_memory_version_and_pack():
    pack = MemoryPack(
        memory_id="mem_001",
        version=1,
        compact_text="基金比较前先统一报告期，避免指标不可比。",
        standard_text="在对比基金时，首先提取各标的最近一期季报截至日，确保区间口径严格一致。",
        token_count=35,
        retrieval_text="基金比较 报告期 统一区间 口径一致",
    )
    v = MemoryVersion(
        memory_id="mem_001",
        version=1,
        content="比较基金时必须统一报告期",
        data_json={"rule": "align_report_date"},
        verification_status=VerificationStatus.VALIDATED,
        pack=pack,
    )
    assert v.version == 1
    assert v.pack.token_count == 35
    assert v.verification_status == VerificationStatus.VALIDATED


def test_fact_and_slot():
    fact = Fact(
        subject="user_123",
        predicate="preferred_report_language",
        value="zh-CN",
        assertion_type=AssertionType.EXPLICIT,
    )
    assert fact.value == "zh-CN"
    assert fact.assertion_type == AssertionType.EXPLICIT

    slot = FactSlot(
        user_id="user_123",
        fact_key="preferred_report_language",
        memory_id="mem_001",
        current_version=1,
        value="zh-CN",
    )
    assert slot.fact_key == "preferred_report_language"
    assert slot.current_version == 1


def test_procedure_validation():
    proc = Procedure(
        task_type="TOOL_RETRY",
        trigger_conditions=["HTTP 504 Gateway Timeout"],
        preconditions=["tool_is_idempotent == true", "retry_budget > 0"],
        steps=["等待 500ms", "发起重试"],
        applicability={"error_code": "TIMEOUT", "idempotent": True},
        validation_rules=["HTTP response status is 200"],
        failure_signatures=["TIMEOUT", "GATEWAY_TIMEOUT"],
        recovery_actions=["RETRY_ONCE"],
    )
    assert proc.task_type == "TOOL_RETRY"
    assert len(proc.preconditions) == 2
    assert proc.applicability["idempotent"] is True
