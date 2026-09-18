import pytest
from unittest.mock import AsyncMock
from memory_service.infrastructure.mysql.memory_repository import MemoryRepository
from memory_service.engine.publication.memory_publisher import MemoryPublisher
from memory_service.engine.pipeline import OfflineMemoryPipeline
from memory_service.domain.enums import MemoryType


@pytest.mark.asyncio
async def test_offline_pipeline_facts_and_evolution(db_session):
    repo = MemoryRepository(db_session)
    mock_vector_repo = AsyncMock()
    mock_vector_repo.upsert_memory_vector = AsyncMock(return_value=True)
    publisher = MemoryPublisher(repo, mock_vector_repo)

    pipeline = OfflineMemoryPipeline(memory_repo=repo, publisher=publisher)

    user_id = "user_pipeline_101"
    session_id_1 = "sess_001"
    session_data_1 = {
        "messages": [
            {"role": "user", "content": "我的风险承受能力是进取型，主要配置股票型和ETF，不要白酒"},
            {"role": "assistant", "content": "好的，已为您记录投资偏好。"},
        ],
        "tool_calls": [],
    }

    res1 = await pipeline.process_session(session_id_1, user_id, session_data_1)
    assert res1["facts_published"] >= 2  # risk_tolerance + excluded_sectors / preferred_asset_classes

    # 验证事实槽位已生效
    risk_slot = await repo.get_fact_slot(user_id, "risk_tolerance")
    assert risk_slot is not None
    assert risk_slot.value == "进取型"
    assert risk_slot.current_version == 1

    # 会话 2：事实演进变更 -> 保守型
    session_id_2 = "sess_002"
    session_data_2 = {
        "messages": [
            {"role": "user", "content": "我最近比较谨慎，风险偏好调整为保守型"},
        ],
    }
    res2 = await pipeline.process_session(session_id_2, user_id, session_data_2)
    assert res2["facts_published"] >= 1

    # 验证槽位演进到版本 2
    updated_slot = await repo.get_fact_slot(user_id, "risk_tolerance")
    assert updated_slot.value == "保守型"
    assert updated_slot.current_version == 2
    assert updated_slot.memory_id == risk_slot.memory_id


@pytest.mark.asyncio
async def test_offline_pipeline_procedure_distillation(db_session):
    repo = MemoryRepository(db_session)
    mock_vector_repo = AsyncMock()
    mock_vector_repo.upsert_memory_vector = AsyncMock(return_value=True)
    publisher = MemoryPublisher(repo, mock_vector_repo)

    pipeline = OfflineMemoryPipeline(memory_repo=repo, publisher=publisher)

    user_id = "user_proc_202"
    session_id = "sess_proc_001"
    session_data = {
        "task_type": "portfolio_rebalance",
        "messages": [
            {"role": "user", "content": "请帮我诊断持仓并调仓"},
        ],
        "tool_calls": [
            {"tool_name": "fetch_portfolio", "args": {"user_id": user_id}},
            {"tool_name": "compute_target_allocation", "args": {"strategy": "balanced"}},
            {"tool_name": "execute_rebalance_order", "args": {"orders": []}},
        ],
        "errors": [
            {"code": "API_TIMEOUT", "message": "Market quote server timeout"},
        ],
        "has_recovered": True,
    }

    res = await pipeline.process_session(session_id, user_id, session_data)
    assert res["procedure_published"] is True

    # 验证 active procedure 已经发布
    active_procs = await repo.list_active_memories_by_type(user_id, MemoryType.PROCEDURE)
    assert len(active_procs) == 1
    mem = active_procs[0]
    assert mem.published_version == 1

    # 验证存储的 Procedure 详情及恢复动作
    ver_detail = await repo.get_version(mem.id, 1)
    assert ver_detail is not None
    assert ver_detail.procedure_detail is not None
    assert ver_detail.procedure_detail.task_type == "portfolio_rebalance"
    assert len(ver_detail.procedure_detail.recovery_actions) >= 1
