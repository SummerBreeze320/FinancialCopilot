import pytest
from unittest.mock import AsyncMock
from memory_service.domain.enums import MemoryType, VerificationStatus
from memory_service.domain.memory import Memory, MemoryVersion
from memory_service.domain.procedure import Procedure
from memory_service.domain.memory_pack import MemoryPack
from memory_service.domain.fact import FactSlot
from memory_service.infrastructure.mysql.memory_repository import MemoryRepository
from memory_service.reader.exact_retriever import ExactRetriever
from memory_service.reader.semantic_retriever import SemanticRetriever
from memory_service.reader.applicability_filter import ApplicabilityFilter
from memory_service.reader.context_assembler import ContextAssembler
from memory_service.reader.recall_service import RecallService, RecallRequest


@pytest.mark.asyncio
async def test_exact_retriever(db_session):
    repo = MemoryRepository(db_session)
    user_id = "user_reader_1"

    # 创建一个发布的事实
    mem = Memory(id="mem_fact_1", user_id=user_id, memory_type=MemoryType.FACT, published_version=1)
    pack = MemoryPack(
        memory_id="mem_fact_1",
        version=1,
        compact_text="[FACT] user.risk_tolerance=稳健型",
        standard_text="Type: FACT\nValue: 稳健型",
        retrieval_text="user risk 稳健型",
        token_count=10,
    )
    ver = MemoryVersion(
        memory_id="mem_fact_1",
        version=1,
        content="User risk is 稳健型",
        verification_status=VerificationStatus.VALIDATED,
        pack=pack,
    )
    await repo.create_memory(mem, ver)
    await repo.switch_published_version("mem_fact_1", 1)

    slot = FactSlot(
        user_id=user_id,
        fact_key="risk_tolerance",
        memory_id="mem_fact_1",
        current_version=1,
        value="稳健型",
    )
    await repo.upsert_fact_slot(slot)

    retriever = ExactRetriever(repo)
    results = await retriever.retrieve(user_id=user_id)
    assert len(results) == 1
    s, v = results[0]
    assert s.fact_key == "risk_tolerance"
    assert v.pack.compact_text == "[FACT] user.risk_tolerance=稳健型"


def test_applicability_filter():
    filter_service = ApplicabilityFilter()

    # 创建一个仅适用于 CN_A_SHARE_FUNDS 市场的经验
    proc = Procedure(
        task_type="portfolio_rebalance",
        trigger_conditions=["rebalance"],
        preconditions=["check kyc"],
        steps=["step 1"],
        applicability={"domain": "finance", "market": "CN_A_SHARE_FUNDS", "supported_tools": ["rebalance_tool"]},
    )
    ver_cn = MemoryVersion(
        memory_id="proc_1",
        version=1,
        content="Rebalance for CN funds",
        procedure_detail=proc,
    )

    # 场景 1：当前是美股市场上下文 -> 应被硬过滤剔除
    filtered_us = filter_service.filter([ver_cn], context={"market": "US_STOCK"})
    assert len(filtered_us) == 0

    # 场景 2：当前是 A 股市场上下文 -> 通过
    filtered_cn = filter_service.filter([ver_cn], context={"market": "CN_A_SHARE_FUNDS"})
    assert len(filtered_cn) == 1


def test_context_assembler_budget_limit():
    assembler = ContextAssembler()

    packs = [
        MemoryPack(
            memory_id=f"m_{i}",
            version=1,
            compact_text=f"[FACT] key_{i}=val_{i}",
            standard_text="",
            retrieval_text="",
            token_count=50,
        )
        for i in range(10)
    ]
    versions = [
        MemoryVersion(
            memory_id=f"m_{i}",
            version=1,
            content=f"Fact {i}",
            pack=packs[i],
        )
        for i in range(10)
    ]

    # 预算仅给 120 token
    bundle = assembler.assemble(versions, token_budget=120)
    assert bundle.total_tokens <= 120
    assert len(bundle.recalled_items) == 2  # 10 header + 50 + 50 = 110 <= 120
    assert "### Long-Term Memory Context" in bundle.compact_context


@pytest.mark.asyncio
async def test_recall_service_pipeline(db_session):
    repo = MemoryRepository(db_session)
    user_id = "user_recall_99"

    # 准备事实
    mem = Memory(id="mem_recall_1", user_id=user_id, memory_type=MemoryType.FACT, published_version=1)
    pack = MemoryPack(
        memory_id="mem_recall_1",
        version=1,
        compact_text="[FACT] user.monthly_budget=5000元",
        standard_text="",
        retrieval_text="",
        token_count=12,
    )
    ver = MemoryVersion(
        memory_id="mem_recall_1",
        version=1,
        content="Monthly budget is 5000",
        verification_status=VerificationStatus.VALIDATED,
        pack=pack,
    )
    await repo.create_memory(mem, ver)
    await repo.switch_published_version("mem_recall_1", 1)

    slot = FactSlot(
        user_id=user_id,
        fact_key="monthly_budget",
        memory_id="mem_recall_1",
        current_version=1,
        value="5000元",
    )
    await repo.upsert_fact_slot(slot)

    exact_retriever = ExactRetriever(repo)
    semantic_retriever = SemanticRetriever(repo)
    recall_svc = RecallService(exact_retriever=exact_retriever, semantic_retriever=semantic_retriever)

    req = RecallRequest(user_id=user_id, token_budget=200)
    bundle = await recall_svc.recall(req)

    assert len(bundle.recalled_items) == 1
    assert "monthly_budget=5000元" in bundle.compact_context
    assert bundle.total_tokens > 0
