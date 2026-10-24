"""单元测试：双路并发召回、GPU/CPU 神经重排器与金融业务加权。"""

import pytest
from unittest.mock import AsyncMock, MagicMock
from app.retrieval.hybrid_retriever import HybridRetriever
from app.retrieval.reranker import NeuralReranker


@pytest.mark.asyncio
async def test_hybrid_retriever_concurrent_recall():
    mock_store = MagicMock()
    mock_embed = MagicMock()
    mock_embed.embed_query = AsyncMock(return_value=[0.1] * 1024)

    c1 = {
        "id": "c1",
        "document_id": "d1",
        "content": "宁德时代动力电池出货量指引",
        "stock_codes": ["300750"],
        "metadata": {"has_table": False},
        "doc_title": "宁德时代三季报点评",
    }
    c2 = {
        "id": "c2",
        "document_id": "d1",
        "content": "2024Q3 财务摘要与毛利率",
        "stock_codes": ["300750"],
        "metadata": {"has_table": True},
        "doc_title": "宁德时代三季报点评",
    }
    c3 = {
        "id": "c3",
        "document_id": "d2",
        "content": "比亚迪海外新能源汽车销量",
        "stock_codes": ["002594"],
        "metadata": {"has_table": False},
        "doc_title": "比亚迪销量报告",
    }

    mock_store.vector_search = AsyncMock(return_value=[c1, c2])
    mock_store.fulltext_search = AsyncMock(return_value=[c2, c3])

    retriever = HybridRetriever(store=mock_store, embedding_client=mock_embed)
    candidates, funnel = await retriever.recall_candidates(
        query="宁德时代 300750",
        top_vector=30,
        top_fulltext=30,
    )

    assert funnel["vector_total_recalled"] == 2
    assert funnel["fulltext_total_recalled"] == 2
    assert funnel["union_candidates"] == 3

    cand_map = {c["id"]: c for c in candidates}
    assert "c1" in cand_map
    assert "c2" in cand_map
    assert "c3" in cand_map

    # 验证召回源记录
    assert cand_map["c1"]["recall_sources"] == ["VECTOR"]
    assert sorted(cand_map["c2"]["recall_sources"]) == ["FULLTEXT", "VECTOR"]
    assert cand_map["c3"]["recall_sources"] == ["FULLTEXT"]


def test_reranker_business_boosts_and_filtering():
    reranker = NeuralReranker(model_name="mock-model", device="cpu")
    # 使用 Mock 替换 cross-encoder 预测分数
    reranker._score_pairs = MagicMock(return_value=[0.40, 0.30, 0.40])

    candidates = [
        {
            "id": "c1",
            "content": "宁德时代出货量",
            "stock_codes": ["300750"],
            "metadata": {"has_table": False},
        },
        {
            "id": "c2",
            "content": "宏观经济利率调整",
            "stock_codes": [],
            "metadata": {"has_table": False},
        },
        {
            "id": "c3",
            "content": "三季度主要财务指标汇总表",
            "stock_codes": [],
            "metadata": {"has_table": True},
        },
    ]

    query = "300750 财务指标表格分析"
    reranked = reranker.rerank(
        query=query,
        candidates=candidates,
        top_k=5,
        score_threshold=0.35,
    )

    # c1: 0.40 + 0.15 (代码完全匹配) = 0.55
    # c3: 0.40 + 0.10 (表格意图匹配) = 0.50
    # c2: 0.30 (未命中加权且 < 0.35 阈值) => 被剔除
    assert len(reranked) == 2
    assert reranked[0]["id"] == "c1"
    assert pytest.approx(reranked[0]["score"], 0.01) == 0.55
    assert reranked[1]["id"] == "c3"
    assert pytest.approx(reranked[1]["score"], 0.01) == 0.50


def test_reranker_rrf_fallback():
    reranker = NeuralReranker(model_name="mock-model", device="cpu")
    candidates = [
        {
            "id": "c1",
            "content": "内容1",
            "stock_codes": [],
            "metadata": {},
            "vector_rank": 1,
            "fulltext_rank": 2,
        },
        {
            "id": "c2",
            "content": "内容2",
            "stock_codes": [],
            "metadata": {},
            "vector_rank": 2,
            "fulltext_rank": 1,
        },
    ]
    reranked = reranker.rerank_rrf(
        query="测试",
        candidates=candidates,
        top_k=2,
    )
    assert len(reranked) == 2
    assert reranked[0]["score"] > 0
