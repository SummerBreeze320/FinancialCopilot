"""综合记忆系统测试套件 — 验证图谱时序记忆、4分类体系、触发词即刻落库、冲突失效、多因子检索衰减、隔天同义词泛化、定期整理与遗忘修剪"""

import pytest
from datetime import datetime, timezone, timedelta
from app.engine.long_term import long_term_engine
from app.engine.short_term import short_term_engine
from app.models.schemas import MemoryCategory, ExplicitMemoryCreate, MemorySearchRequest


@pytest.fixture(autouse=True)
def clean_memory_store():
    long_term_engine._facts_store.clear()
    long_term_engine._user_facts_index.clear()
    short_term_engine._in_memory_store.clear()
    short_term_engine._in_memory_owners.clear()
    yield


@pytest.mark.asyncio
async def test_trigger_keyword_immediate_ingestion():
    """验证需求：关键字‘记住’即刻落库到长期记忆库"""
    # 用户在会话中发送包含“记住”的消息
    await short_term_engine.add_message(
        user_id="user_a",
        session_id="conv_101",
        role="user",
        content="请记住：不要给我推荐任何微盘股和ST股票！",
    )

    # 验证长期记忆中已即刻落库
    res = await long_term_engine.search(user_id="user_a", query="微盘股")
    assert len(res.results) >= 1
    hit = res.results[0]
    assert "微盘股" in hit.fact or "st" in hit.fact.lower()
    assert hit.category == MemoryCategory.USER_FEEDBACK.value


@pytest.mark.asyncio
async def test_four_category_architecture_isolation():
    """验证需求：记忆四分类体系架构 (USER_PROFILE, USER_FEEDBACK, TASK_EPISODIC, RESOURCE_REFERENCE) 及其检索隔离"""
    user_id = "trader_88"

    # 1. USER_PROFILE: 用户画像偏好
    await long_term_engine.add_explicit_memory(
        user_id=user_id,
        content="我是稳健型投资风格，偏好大盘宽基与红利资产",
        category=MemoryCategory.USER_PROFILE,
        importance=0.8,
    )

    # 2. USER_FEEDBACK: 用户规则反馈约束
    await long_term_engine.add_explicit_memory(
        user_id=user_id,
        content="每次回答请务必附上最大回撤指标",
        category=MemoryCategory.USER_FEEDBACK,
        importance=0.9,
    )

    # 3. TASK_EPISODIC: 任务情境决策事实
    await long_term_engine.add_explicit_memory(
        user_id=user_id,
        content="在医药大跌时决定加仓医疗ETF",
        category=MemoryCategory.TASK_EPISODIC,
        scenario="2026年3月医药深度回调",
        reason="估值处于历史5%极低分位",
        importance=0.85,
    )

    # 4. RESOURCE_REFERENCE: 外部资源/标的引用
    await long_term_engine.add_explicit_memory(
        user_id=user_id,
        content="重点跟踪易方达蓝筹精选(005827)及其重仓消费股变动",
        category=MemoryCategory.RESOURCE_REFERENCE,
        importance=0.7,
    )

    # 分类隔离检索验证
    profile_res = await long_term_engine.search(user_id=user_id, query="风格", category=MemoryCategory.USER_PROFILE.value)
    assert len(profile_res.results) == 1
    assert "稳健型" in profile_res.results[0].fact

    feedback_res = await long_term_engine.search(user_id=user_id, query="回撤", category=MemoryCategory.USER_FEEDBACK.value)
    assert len(feedback_res.results) == 1
    assert "最大回撤" in feedback_res.results[0].fact

    task_res = await long_term_engine.search(user_id=user_id, query="医疗", category=MemoryCategory.TASK_EPISODIC.value)
    assert len(task_res.results) == 1
    assert task_res.results[0].scenario == "2026年3月医药深度回调"
    assert task_res.results[0].reason == "估值处于历史5%极低分位"

    res_res = await long_term_engine.search(user_id=user_id, query="005827", category=MemoryCategory.RESOURCE_REFERENCE.value)
    assert len(res_res.results) == 1
    assert "易方达蓝筹精选" in res_res.results[0].fact


@pytest.mark.asyncio
async def test_conflict_detection_and_temporal_invalidation():
    """验证需求：冲突检测 — 新记忆生效 valid_at=now，旧记忆失效标记 invalid_at=now，时点回溯可查"""
    user_id = "investor_01"
    t0 = datetime(2026, 1, 1, 10, 0, tzinfo=timezone.utc)
    t1 = datetime(2026, 6, 1, 10, 0, tzinfo=timezone.utc)

    # t0: 用户偏好为保守型
    m1 = await long_term_engine.add_explicit_memory(
        user_id=user_id,
        content="我是保守型投资者，风险承受能力很低",
        category=MemoryCategory.USER_PROFILE,
        reference_time=t0,
    )

    # 验证 t0 写入状态
    assert m1.uuid in long_term_engine._facts_store
    assert long_term_engine._facts_store[m1.uuid]["invalid_at"] is None

    # t1: 用户修改偏好为进取型
    m2 = await long_term_engine.add_explicit_memory(
        user_id=user_id,
        content="记住我偏好进取型投资风格，要配置成长赛道",
        category=MemoryCategory.USER_PROFILE,
        reference_time=t1,
    )

    # 验证冲突检测：m1 应该被标记失效，invalid_at == t1
    assert m1.uuid in m2.invalidated_uuids
    fact1 = long_term_engine._facts_store[m1.uuid]
    assert fact1["invalid_at"] == t1

    # 当前检索：只返回最新的进取型事实
    current_search = await long_term_engine.search(user_id=user_id, query="投资风格")
    assert len(current_search.results) == 1
    assert "进取型" in current_search.results[0].fact

    # 时点回溯检索 (检索 t0+1天，当时进取型尚未发生，保守型仍有效)
    t0_probe = datetime(2026, 1, 2, 10, 0, tzinfo=timezone.utc)
    temporal_search = await long_term_engine.search_by_time(
        query="投资风格",
        at_time=t0_probe,
        user_id=user_id,
    )
    assert len(temporal_search.results) == 1
    assert "保守型" in temporal_search.results[0].fact


@pytest.mark.asyncio
async def test_multi_factor_hybrid_ranking_with_decay_and_importance():
    """验证需求：多因子检索重排 (语义相似度 + 时间衰退指数 + 重要性权重)"""
    user_id = "quant_user"
    now = datetime.now(timezone.utc)

    # 事实 A: 180天前的旧事实，中等重要性
    old_time = now - timedelta(days=180)
    await long_term_engine.add_explicit_memory(
        user_id=user_id,
        content="偏好大盘价值龙头标的",
        category=MemoryCategory.USER_PROFILE,
        importance=0.5,
        reference_time=old_time,
    )

    # 事实 B: 刚刚建立的高重要性事实
    await long_term_engine.add_explicit_memory(
        user_id=user_id,
        content="偏好大盘成长风格与科技指数",
        category=MemoryCategory.USER_PROFILE,
        importance=0.95,
        reference_time=now,
    )

    res = await long_term_engine.search(
        user_id=user_id,
        query="大盘偏好",
        w_semantic=0.4,
        w_recency=0.3,
        w_importance=0.3,
        half_life_days=30.0,
    )

    assert len(res.results) == 2
    # 刚刚写入的高重要性事实总得分显著高于180天前的事实
    top_hit = res.results[0]
    second_hit = res.results[1]
    assert "科技" in top_hit.fact
    assert top_hit.score > second_hit.score
    assert top_hit.recency_score > second_hit.recency_score
    assert top_hit.importance_score > second_hit.importance_score


@pytest.mark.asyncio
async def test_next_day_synonym_generalization_evaluation():
    """验证需求：系统验证 — 隔天测试（换句式/同义词泛化召回）"""
    user_id = "test_user_day1"
    yesterday = datetime.now(timezone.utc) - timedelta(days=1)

    # Day 1: 用户立下规则与投资决策
    await long_term_engine.add_explicit_memory(
        user_id=user_id,
        content="记住：千万不要推荐微盘股与高风险垃圾股！",
        category=MemoryCategory.USER_FEEDBACK,
        reference_time=yesterday,
    )

    await long_term_engine.add_explicit_memory(
        user_id=user_id,
        content="我在医药深度大跌时果断买入医疗ETF，因为估值处于历史5%极值",
        category=MemoryCategory.TASK_EPISODIC,
        reference_time=yesterday,
    )

    # Day 2: 用户使用完全不同的句式、同义词提问
    # 1. 换句式查询微盘: "小盘股可以建仓吗？" -> 应当泛化召回微盘股规则
    res1 = await long_term_engine.search(user_id=user_id, query="小盘股可以买吗")
    assert len(res1.results) >= 1
    assert "微盘股" in res1.results[0].fact

    # 2. 换同义词查询决策: "我之前买入生物医药基金的理由是什么？" -> 应当泛化召回医疗ETF决策
    res2 = await long_term_engine.search(user_id=user_id, query="生物医药基金加仓动机理由")
    assert len(res2.results) >= 1
    assert "医疗ETF" in res2.results[0].fact


@pytest.mark.asyncio
async def test_memory_consolidation_and_deduplication():
    """验证需求：后台定期整理（去重、归并同类型记忆）"""
    user_id = "user_consolidate"

    # 写入两条高度重合的记忆
    await long_term_engine.add_explicit_memory(
        user_id=user_id,
        content="不要给我推荐任何创业板和科创板标的",
        category=MemoryCategory.USER_FEEDBACK,
        importance=0.7,
    )
    await long_term_engine.add_explicit_memory(
        user_id=user_id,
        content="禁止推荐任何创业板和科创板标的",
        category=MemoryCategory.USER_FEEDBACK,
        importance=0.7,
    )

    # 运行定期整理
    con_res = await long_term_engine.consolidate(user_id=user_id)
    assert con_res.processed_count == 2
    assert con_res.deduplicated_count >= 1

    # 整理后只保留 1 条有效记忆，且重要性得到强化
    search_res = await long_term_engine.search(user_id=user_id, query="创业板")
    assert len(search_res.results) == 1
    assert search_res.results[0].importance >= 0.79


@pytest.mark.asyncio
async def test_memory_pruning_and_forgetting():
    """验证需求：清理遗忘机制（长期未检索冷记忆、被新事实推翻记忆、低价值记忆修剪）"""
    user_id = "user_prune"
    now = datetime.now(timezone.utc)
    ancient_time = now - timedelta(days=120)

    # 1. 冷记忆 (120天前，从未被访问 access_count=0)
    await long_term_engine.add_explicit_memory(
        user_id=user_id,
        content="120天前随口提过一句某冷门转债",
        importance=0.4,
        reference_time=ancient_time,
    )

    # 2. 被新事实推翻的记忆
    m_old = await long_term_engine.add_explicit_memory(
        user_id=user_id,
        content="我是保守型风格",
        category=MemoryCategory.USER_PROFILE,
    )
    await long_term_engine.add_explicit_memory(
        user_id=user_id,
        content="我是进取型风格",
        category=MemoryCategory.USER_PROFILE,
    )

    # 3. 低价值琐碎记忆 (importance=0.1)
    await long_term_engine.add_explicit_memory(
        user_id=user_id,
        content="今天天气还不错",
        importance=0.1,
    )

    # 执行清理修剪 (修剪失效记忆、90天未访问冷记忆、低于0.3的低价值记忆)
    prune_res = await long_term_engine.prune(
        user_id=user_id,
        max_idle_days=90,
        min_importance=0.3,
        prune_invalidated=True,
    )

    assert prune_res.pruned_invalidated >= 1
    assert prune_res.pruned_cold >= 1
    assert prune_res.pruned_low_importance >= 1

    # 活跃的“进取型风格”依然保留
    active_res = await long_term_engine.search(user_id=user_id, query="进取型风格")
    assert len(active_res.results) == 1
    assert "进取型" in active_res.results[0].fact
