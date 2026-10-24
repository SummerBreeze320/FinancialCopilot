"""单元测试：全链路耗时监控与归因透视诊断器。"""

import time
from app.diagnostics.tracer import LatencyTracer
from app.diagnostics.explainer import AttributionExplainer


def test_latency_tracer_measurements():
    tracer = LatencyTracer()
    with tracer.measure("embedding_latency"):
        time.sleep(0.01)

    with tracer.measure("vector_query_latency"):
        time.sleep(0.01)

    breakdown = tracer.get_breakdown_ms()
    assert "embedding_latency" in breakdown
    assert "vector_query_latency" in breakdown
    assert breakdown["embedding_latency"] >= 8.0
    assert breakdown["vector_query_latency"] >= 8.0
    assert breakdown["total_latency"] >= 16.0


def test_attribution_explainer_saturation_warning():
    explainer = AttributionExplainer()
    # 构造来自粗召回末梢 (例如第 29 名) 的高分切片
    top_chunks = [
        {
            "chunk_id": "c_tail",
            "score": 0.88,
            "vector_rank": 29,
            "fulltext_rank": None,
        },
        {
            "chunk_id": "c_head",
            "score": 0.92,
            "vector_rank": 2,
            "fulltext_rank": 1,
        },
    ]

    warnings = explainer.check_pool_saturation(top_chunks, vector_pool_size=30)
    assert len(warnings) == 1
    assert "Candidate Pool Saturation" in warnings[0]
    assert "Rank 29/30" in warnings[0]


def test_attribution_explainer_root_cause_decision_tree():
    explainer = AttributionExplainer()
    candidates = [
        {"id": "c1", "vector_rank": 15, "score": 0.32},
    ]
    final_top_k = []

    # 1. 候选集不存在
    res_absent = explainer.diagnose_failure(
        target_chunk_id="c_missing",
        candidates=candidates,
        final_top_k=final_top_k,
    )
    assert res_absent["phase"] in ["PHASE_1_CHUNKING", "PHASE_2_EMBEDDING"]
    assert "候选池中完全不存在" in res_absent["reason"]

    # 2. 候选集存在但被重排截断过滤
    res_dropped = explainer.diagnose_failure(
        target_chunk_id="c1",
        candidates=candidates,
        final_top_k=final_top_k,
    )
    assert res_dropped["phase"] in ["PHASE_3_RECALL_TRUNCATED", "PHASE_4_RERANK_DISTORTION"]
    assert "重排" in res_dropped["reason"] or "截断" in res_dropped["reason"]
