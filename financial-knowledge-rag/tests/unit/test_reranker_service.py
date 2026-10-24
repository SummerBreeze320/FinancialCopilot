"""单元测试：远程 Docker Reranker 微服务客户端与优雅降级。"""

import pytest
from unittest.mock import AsyncMock, MagicMock, patch
import httpx
from app.retrieval.reranker import NeuralReranker


def test_reranker_service_call_and_boosts():
    reranker = NeuralReranker(
        backend="service",
        service_url="http://127.0.0.1:8088/v1/rerank",
    )

    candidates = [
        {
            "id": "c1",
            "content": "宁德时代2024Q3动力电池出货量98.5GWh",
            "stock_codes": ["300750"],
            "metadata": {"has_table": False},
        },
        {
            "id": "c2",
            "content": "宏观经济研究报告",
            "stock_codes": [],
            "metadata": {"has_table": False},
        },
    ]

    with patch("httpx.Client.post") as mock_post:
        mock_post.return_value = MagicMock(
            status_code=200,
            json=lambda: [
                {"index": 0, "score": 0.45},
                {"index": 1, "score": 0.20},
            ],
            raise_for_status=lambda: None,
        )

        results = reranker.rerank(
            query="300750 出货量分析",
            candidates=candidates,
            top_k=5,
            score_threshold=0.35,
        )

        assert len(results) == 1
        # c1 基础分 0.45 + 0.15 (股票代码匹配) = 0.60
        assert results[0]["id"] == "c1"
        assert pytest.approx(results[0]["score"], 0.01) == 0.60
        # c2 0.20 低于 0.35 阈值被过滤
        assert mock_post.called


def test_reranker_service_fallback_on_error():
    reranker = NeuralReranker(
        backend="service",
        service_url="http://127.0.0.1:8088/v1/rerank",
    )

    candidates = [
        {
            "id": "c1",
            "content": "内容1",
            "stock_codes": [],
            "vector_rank": 1,
            "fulltext_rank": 2,
        },
        {
            "id": "c2",
            "content": "内容2",
            "stock_codes": [],
            "vector_rank": 2,
            "fulltext_rank": 1,
        },
    ]

    with patch("httpx.Client.post", side_effect=httpx.ConnectError("Docker service connection failed")):
        # 远程服务未就绪或宕机时，必须平滑降级为 RRF 融合打分，绝不抛出未捕获异常
        results = reranker.rerank(
            query="测试",
            candidates=candidates,
            top_k=2,
            score_threshold=0.0,
        )

        assert len(results) == 2
        assert "score" in results[0]
        assert results[0]["score"] > 0
