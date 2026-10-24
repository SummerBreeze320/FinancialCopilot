from pathlib import Path


def test_dashboard_real_mode_never_silently_falls_back_to_mock_data() -> None:
    """真实测试页面在后端失败时必须展示错误，不得伪造 RAG 或 DSL 结果。"""
    dashboard = (Path(__file__).resolve().parents[2] / "static" / "index.html").read_text(encoding="utf-8")

    assert "getMockRagResult(query)" not in dashboard
    assert "getMockE2eResult(query, inheritDsl ? currentCachedDsl : null)" not in dashboard
    assert "renderRequestError" in dashboard


def test_dashboard_rag_mode_uses_workflow_mention_trace() -> None:
    dashboard = (Path(__file__).resolve().parents[2] / "static" / "index.html").read_text(encoding="utf-8")

    assert "/v1/fund-screening/rag-trace" in dashboard
    assert "renderRagTraceResult" in dashboard
    assert "/v1/fund-screening/rag-search" not in dashboard


def test_dashboard_waits_for_the_real_llm_compilation_budget() -> None:
    dashboard = (Path(__file__).resolve().parents[2] / "static" / "index.html").read_text(encoding="utf-8")

    assert "AbortSignal.timeout(300000)" in dashboard
    assert "真实 LLM 编译超时" in dashboard
