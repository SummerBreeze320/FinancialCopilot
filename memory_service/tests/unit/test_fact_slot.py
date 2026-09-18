import pytest
from memory_service.domain.fact import FactSlot
from memory_service.infrastructure.mysql.memory_repository import MemoryRepository


@pytest.mark.asyncio
async def test_fact_slot_upsert_and_query(db_session):
    repo = MemoryRepository(db_session)

    # 1. 插入新槽位
    slot1 = FactSlot(
        user_id="user_999",
        fact_key="preferred_currency",
        memory_id="mem_fact_01",
        current_version=1,
        value="CNY",
    )
    await repo.upsert_fact_slot(slot1)

    # 2. 查询并验证
    res = await repo.get_fact_slot("user_999", "preferred_currency")
    assert res is not None
    assert res.value == "CNY"
    assert res.current_version == 1
    assert res.memory_id == "mem_fact_01"

    # 3. 演化更新同槽位为 v2
    slot2 = FactSlot(
        user_id="user_999",
        fact_key="preferred_currency",
        memory_id="mem_fact_01",
        current_version=2,
        value="USD",
    )
    await repo.upsert_fact_slot(slot2)

    # 4. 再次查询确认被覆盖更新且版本递增
    updated = await repo.get_fact_slot("user_999", "preferred_currency")
    assert updated is not None
    assert updated.value == "USD"
    assert updated.current_version == 2

    # 5. 验证用户槽位列表
    all_slots = await repo.list_user_fact_slots("user_999")
    assert len(all_slots) == 1
    assert all_slots[0].fact_key == "preferred_currency"
