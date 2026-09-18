import time
import pytest
from unittest.mock import AsyncMock
from memory_service.domain.enums import MemoryType
from memory_service.infrastructure.mysql.memory_repository import MemoryRepository
from memory_service.engine.publication.memory_publisher import MemoryPublisher
from memory_service.engine.pipeline import OfflineMemoryPipeline
from memory_service.reader.exact_retriever import ExactRetriever
from memory_service.reader.semantic_retriever import SemanticRetriever
from memory_service.reader.recall_service import RecallService, RecallRequest


@pytest.mark.asyncio
async def test_memory_evolution_benchmark(db_session):
    """
    基准评测：多轮会话事实演进与偏好冲突消解正确性评测
    """
    repo = MemoryRepository(db_session)
    mock_vector = AsyncMock()
    mock_vector.upsert_memory_vector = AsyncMock(return_value=True)
    publisher = MemoryPublisher(repo, mock_vector)
    pipeline = OfflineMemoryPipeline(memory_repo=repo, publisher=publisher)

    user_id = "eval_user_benchmark_1"

    # Turn 1: 声明初始偏好
    turn1 = {
        "messages": [
            {"role": "user", "content": "我的风险偏好是稳健型，每月投入2000元，排除房地产"},
        ]
    }
    await pipeline.process_session("sess_t1", user_id, turn1)

    slot_risk = await repo.get_fact_slot(user_id, "risk_tolerance")
    slot_budget = await repo.get_fact_slot(user_id, "monthly_budget")
    assert slot_risk.value == "稳健型"
    assert slot_risk.current_version == 1
    assert slot_budget.value == "2000元"

    # Turn 2: 声明扩大排除行业（集合精化 REFINE）
    turn2 = {
        "messages": [
            {"role": "user", "content": "我也不买白酒和医药"},
        ]
    }
    await pipeline.process_session("sess_t2", user_id, turn2)
    slot_excluded = await repo.get_fact_slot(user_id, "excluded_sectors")
    assert "白酒" in slot_excluded.value
    assert "医药" in slot_excluded.value

    # Turn 3: 偏好修正（单值覆盖 CORRECT -> v2）
    turn3 = {
        "messages": [
            {"role": "user", "content": "我想调整一下，风险偏好改为进取型，每月投入5000元"},
        ]
    }
    await pipeline.process_session("sess_t3", user_id, turn3)
    slot_risk_v2 = await repo.get_fact_slot(user_id, "risk_tolerance")
    assert slot_risk_v2.value == "进取型"
    assert slot_risk_v2.current_version == 2
    assert slot_risk_v2.memory_id == slot_risk.memory_id


@pytest.mark.asyncio
async def test_online_reader_latency_benchmark(db_session):
    """
    基准评测：在线 Reader 检索延迟与预算控制评测（纯只读，零 LLM）
    """
    repo = MemoryRepository(db_session)
    mock_vector = AsyncMock()
    mock_vector.upsert_memory_vector = AsyncMock(return_value=True)
    publisher = MemoryPublisher(repo, mock_vector)
    pipeline = OfflineMemoryPipeline(memory_repo=repo, publisher=publisher)

    user_id = "eval_user_latency"
    await pipeline.process_session(
        "sess_lat",
        user_id,
        {
            "messages": [
                {"role": "user", "content": "我的风险承受能力是平衡型，喜欢指数基金和ETF"},
            ],
            "task_type": "fund_screening",
            "tool_calls": [{"tool_name": "screen_funds", "args": {"type": "index"}}],
        }
    )

    exact_retriever = ExactRetriever(repo)
    semantic_retriever = SemanticRetriever(repo)
    recall_svc = RecallService(exact_retriever=exact_retriever, semantic_retriever=semantic_retriever)

    # 测量 50 次在线召回的平均延迟
    times = []
    for _ in range(50):
        start = time.perf_counter()
        bundle = await recall_svc.recall(RecallRequest(user_id=user_id, token_budget=300))
        elapsed_ms = (time.perf_counter() - start) * 1000
        times.append(elapsed_ms)
        assert bundle.total_tokens <= 300
        assert len(bundle.recalled_items) >= 1

    avg_ms = sum(times) / len(times)
    p95_ms = sorted(times)[int(len(times) * 0.95)]
    # 保证平均耗时在极速范围内
    assert avg_ms < 50.0
    print(f"\n[Benchmark] Recall latency: Avg = {avg_ms:.2f}ms, P95 = {p95_ms:.2f}ms")
