import pytest
from unittest.mock import AsyncMock
from memory_service.domain.enums import MemoryType, MemoryStatus, VerificationStatus
from memory_service.domain.memory import Memory, MemoryVersion
from memory_service.domain.memory_pack import MemoryPack
from memory_service.infrastructure.mysql.memory_repository import MemoryRepository
from memory_service.engine.publication.memory_publisher import MemoryPublisher, PublicationError


@pytest.mark.asyncio
async def test_publisher_gate_validation(db_session):
    repo = MemoryRepository(db_session)
    mock_vector_repo = AsyncMock()
    publisher = MemoryPublisher(repo, mock_vector_repo)

    mem = Memory(id="mem_test_01", user_id="user_888", memory_type=MemoryType.FACT)

    # 1. 尝试发布 UNVERIFIED 版本 -> 应当抛出门禁异常
    v_unverified = MemoryVersion(
        memory_id="mem_test_01",
        version=1,
        content="未经核实的事实",
        verification_status=VerificationStatus.UNVERIFIED,
    )
    with pytest.raises(PublicationError, match="must be VALIDATED"):
        await publisher.publish(mem, v_unverified)

    # 2. 尝试发布未编译 Pack 的版本 -> 应当抛出门禁异常
    v_no_pack = MemoryVersion(
        memory_id="mem_test_01",
        version=1,
        content="已验证但未编译",
        verification_status=VerificationStatus.VALIDATED,
        pack=None,
    )
    with pytest.raises(PublicationError, match="MemoryPack has not been compiled"):
        await publisher.publish(mem, v_no_pack)


@pytest.mark.asyncio
async def test_publisher_atomic_switch(db_session):
    repo = MemoryRepository(db_session)
    mock_vector_repo = AsyncMock()
    publisher = MemoryPublisher(repo, mock_vector_repo)

    # 创建初始记忆草稿与 v1
    pack_v1 = MemoryPack(
        memory_id="mem_test_02",
        version=1,
        compact_text="报告期必须统一。",
        standard_text="完整操作指南：报告期必须统一",
        token_count=15,
        retrieval_text="报告期 统一",
    )
    v1 = MemoryVersion(
        memory_id="mem_test_02",
        version=1,
        content="报告期必须统一",
        data_json={"rule": "align_date"},
        verification_status=VerificationStatus.VALIDATED,
        pack=pack_v1,
    )
    mem = Memory(id="mem_test_02", user_id="user_888", memory_type=MemoryType.PROCEDURE)
    await repo.create_memory(mem, v1)

    # 发布 v1
    published = await publisher.publish(mem, v1, embedding=[0.1] * 1536)
    assert published is True

    # 验证数据库状态为 ACTIVE，published_version 为 1
    active_v1 = await repo.get_published_version("mem_test_02")
    assert active_v1 is not None
    assert active_v1.version == 1
    assert active_v1.content == "报告期必须统一"

    # 模拟离线演进生成 v2
    pack_v2 = MemoryPack(
        memory_id="mem_test_02",
        version=2,
        compact_text="报告期必须统一；成立不足标明实际区间。",
        standard_text="完整操作指南：报告期必须统一；若成立不足目标区间标明实际区间",
        token_count=25,
        retrieval_text="报告期 统一 成立不足 实际区间",
    )
    v2 = MemoryVersion(
        memory_id="mem_test_02",
        version=2,
        content="报告期必须统一，成立不足需标明",
        data_json={"rule": "align_date_with_fallback"},
        verification_status=VerificationStatus.VALIDATED,
        pack=pack_v2,
    )
    await repo.append_version(v2)

    # 在 v2 发布前，在线读取依然稳定在 v1
    still_v1 = await repo.get_published_version("mem_test_02")
    assert still_v1.version == 1

    # 门禁发布 v2
    await publisher.publish(mem, v2, embedding=[0.2] * 1536)

    # 验证原子切换到了 v2
    active_v2 = await repo.get_published_version("mem_test_02")
    assert active_v2.version == 2
    assert active_v2.content == "报告期必须统一，成立不足需标明"
    assert active_v2.pack.token_count == 25

    # 软删除测试：立即阻断在线召回
    await repo.soft_delete_memory("mem_test_02")
    deleted_result = await repo.get_published_version("mem_test_02")
    assert deleted_result is None  # 状态已非 ACTIVE，立即阻断
