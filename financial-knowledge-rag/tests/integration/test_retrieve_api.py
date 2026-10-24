"""集成测试：FastAPI 检索路由、文档上传与流式问答接口契约测试。"""

import io
import pytest
from unittest.mock import AsyncMock, MagicMock
from fastapi.testclient import TestClient

from app.api.routes import (
    get_hybrid_retriever,
    get_qa_generator,
    get_reranker,
    get_vector_store,
)
from app.main import app


@pytest.fixture
def client():
    return TestClient(app)


def test_post_retrieve_endpoint(client):
    mock_candidates = [
        {
            "id": "c1",
            "document_id": "d1",
            "content": "宁德时代 2024Q3 毛利率为 28.2%",
            "stock_codes": ["300750"],
            "fund_codes": [],
            "metadata": {"has_table": True},
            "doc_title": "宁德时代三季报点评",
            "page_number": 3,
            "section_title": "财务分析",
            "recall_sources": ["VECTOR", "FULLTEXT"],
            "vector_rank": 1,
            "fulltext_rank": 1,
            "score": 0.88,
        }
    ]
    mock_funnel = {
        "vector_total_recalled": 10,
        "fulltext_total_recalled": 8,
        "union_candidates": 12,
    }

    mock_retriever = MagicMock()
    mock_retriever.recall_candidates = AsyncMock(return_value=(mock_candidates, mock_funnel))

    mock_reranker = MagicMock()
    mock_reranker.rerank = MagicMock(return_value=mock_candidates)

    app.dependency_overrides[get_hybrid_retriever] = lambda: mock_retriever
    app.dependency_overrides[get_reranker] = lambda: mock_reranker

    try:
        payload = {
            "query": "宁德时代2024年三季报毛利率",
            "top_k": 3,
            "score_threshold": 0.35,
            "filters": {
                "stock_codes": ["300750"],
            },
            "rerank_strategy": "neural",
            "explain": True,
        }

        resp = client.post("/api/knowledge/retrieve", json=payload)
        assert resp.status_code == 200
        data = resp.json()

        assert data["query"] == "宁德时代2024年三季报毛利率"
        assert len(data["chunks"]) == 1
        assert data["chunks"][0]["chunk_id"] == "c1"
        assert data["chunks"][0]["has_table"] is True
        assert data["chunks"][0]["score"] == 0.88

        assert "diagnostics" in data
        assert "recall_funnel" in data["diagnostics"]
        assert data["diagnostics"]["recall_funnel"]["union_candidates"] == 12
        assert "latency_breakdown_ms" in data["diagnostics"]
    finally:
        app.dependency_overrides.clear()


def test_upload_and_status_endpoints(client):
    file_content = b"# Test Document\n\nSample content"
    files = {"file": ("test.md", io.BytesIO(file_content), "text/markdown")}
    data = {
        "cognitive_layer": "GROUND_TRUTH",
        "category_path": "GROUND_TRUTH.PERIODIC",
        "institution": "中金公司",
    }

    mock_store = MagicMock()
    mock_store.save_document = AsyncMock()

    app.dependency_overrides[get_vector_store] = lambda: mock_store
    try:
        resp = client.post("/api/knowledge/documents/upload", files=files, data=data)
        assert resp.status_code == 200
        res_data = resp.json()
        assert "document_id" in res_data
        assert res_data["status"] == "PROCESSING"

        doc_id = res_data["document_id"]
        status_resp = client.get(f"/api/knowledge/documents/{doc_id}/status")
        assert status_resp.status_code == 200
        status_data = status_resp.json()
        assert status_data["document_id"] == doc_id
    finally:
        app.dependency_overrides.clear()


def test_stream_query_endpoint(client):
    mock_retriever = MagicMock()
    mock_retriever.recall_candidates = AsyncMock(return_value=([], {}))
    mock_reranker = MagicMock()
    mock_reranker.rerank = MagicMock(return_value=[])

    async def mock_gen(query, chunks):
        yield "data: {\"content\": \"宁德时代毛利率提升。\"}\n\n"
        yield "data: [DONE]\n\n"

    mock_qa = MagicMock()
    mock_qa.generate_stream = mock_gen

    app.dependency_overrides[get_hybrid_retriever] = lambda: mock_retriever
    app.dependency_overrides[get_reranker] = lambda: mock_reranker
    app.dependency_overrides[get_qa_generator] = lambda: mock_qa

    try:
        payload = {
            "query": "宁德时代毛利率趋势",
            "top_k": 3,
        }
        resp = client.post("/api/knowledge/query/stream", json=payload)
        assert resp.status_code == 200
        assert "text/event-stream" in resp.headers["content-type"]
        assert "宁德时代毛利率提升" in resp.text
    finally:
        app.dependency_overrides.clear()
