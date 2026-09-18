import pytest
from unittest.mock import AsyncMock
from memory_service.infrastructure.mysql.memory_repository import MemoryRepository
from memory_service.engine.publication.memory_publisher import MemoryPublisher
from memory_service.poc.mem0_adapter import Mem0FactAdapter
from memory_service.poc.reme_adapter import ReMeExperienceAdapter
from memory_service.poc.unified_poc_pipeline import UnifiedPocPipeline
from memory_service.reader.exact_retriever import ExactRetriever
from memory_service.reader.semantic_retriever import SemanticRetriever
from memory_service.reader.recall_service import RecallService, RecallRequest


def test_poc_mem0_stateless_extraction():
    """
    PoC 验证 1: Mem0 无状态事实提取
    验证无需引入 Mem0 本地向量库，即可提取规范原子事实
    """
    adapter = Mem0FactAdapter()
    messages = [
        {"role": "user", "content": "我的风险承受能力是进取型，每月打算定投5000元，不要医药和白酒"},
    ]
    facts = adapter.extract_facts_stateless(messages)
    assert len(facts) >= 2
    predicates = {f.predicate: f.value for f in facts}
    assert predicates.get("risk_tolerance") == "进取型"
    assert predicates.get("monthly_budget") == "5000元"


def test_poc_reme_trajectory_distillation_and_refine():
    """
    PoC 验证 2: ReMe 轨迹经验蒸馏与效用精化
    验证无需写入本地 Markdown 文件，即可提炼 Procedure 并完成效用增量合并
    """
    adapter = ReMeExperienceAdapter()
    tool_calls = [
        {"tool_name": "fetch_user_kyc", "description": "Fetch KYC risk level"},
        {"tool_name": "screen_target_funds", "description": "Filter funds"},
    ]
    errors = [{"code": "NETWORK_TIMEOUT", "message": "API timeout"}]

    # 首次蒸馏
    proc_v1 = adapter.distill_trajectory("fund_screening", tool_calls, "SUCCESS", errors)
    assert proc_v1.task_type == "fund_screening"
    assert len(proc_v1.steps) == 2
    assert len(proc_v1.failure_signatures) == 1
    assert "NETWORK_TIMEOUT" in proc_v1.recovery_actions[0]

    # 再次蒸馏带有新错误的轨迹，执行 ReMe 效用精化
    new_errors = [{"code": "RISK_MISMATCH", "message": "Risk level mismatch"}]
    proc_new = adapter.distill_trajectory("fund_screening", tool_calls, "SUCCESS", new_errors)

    action, refined_proc = adapter.utility_refine(proc_v1, proc_new)
    assert action.value == "REFINE"
    # 验证新旧错误对策得到有效合并
    assert len(refined_proc.recovery_actions) == 2


@pytest.mark.asyncio
async def test_poc_unified_pipeline_and_recall(db_session):
    """
    PoC 验证 3: 统一结构化入库与在线只读召回
    验证 Mem0 (事实) 与 ReMe (经验) 共同接入 MySQL 权威版本与在线 Reader
    """
    repo = MemoryRepository(db_session)
    mock_vector = AsyncMock()
    mock_vector.upsert_memory_vector = AsyncMock(return_value=True)
    publisher = MemoryPublisher(repo, mock_vector)

    pipeline = UnifiedPocPipeline(repo, publisher)

    user_id = "poc_user_001"
    session_data = {
        "task_type": "fund_investment",
        "messages": [
            {"role": "user", "content": "我的风险偏好是成长型，主要配置股票型基金"},
        ],
        "tool_calls": [
            {"tool_name": "check_balance", "description": "Check wallet balance"},
            {"tool_name": "create_order", "description": "Submit purchase order"},
        ],
        "outcome": "SUCCESS",
    }

    res = await pipeline.ingest_session("sess_poc_1", user_id, session_data)
    assert res["facts_published"] >= 1
    assert res["procedure_published"] is True

    # 验证在线 Reader 统一召回事实与过程经验
    exact_retriever = ExactRetriever(repo)
    semantic_retriever = SemanticRetriever(repo)
    recall_svc = RecallService(exact_retriever, semantic_retriever)

    req = RecallRequest(user_id=user_id, task_type="fund_investment", token_budget=400)
    bundle = await recall_svc.recall(req)

    assert len(bundle.recalled_items) >= 2
    assert "risk_tolerance" in bundle.compact_context
    assert "task=fund_investment" in bundle.compact_context
    assert any("ReMe Strategy" in item.content for item in bundle.recalled_items)
    assert bundle.total_tokens <= 400
