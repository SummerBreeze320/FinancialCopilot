import pytest
from unittest.mock import AsyncMock, patch
from app.retrieval.embedding_client import EmbeddingClient
from app.retrieval.pgvector_store import PgVectorStore


@pytest.mark.asyncio
async def test_embedding_client_batching():
    client = EmbeddingClient(
        base_url="http://127.0.0.1:11434/v1",
        api_key="local",
        model="qwen3-embedding:0.6b",
        dim=1024
    )
    with patch("httpx.AsyncClient.post") as mock_post:
        mock_resp = AsyncMock()
        mock_resp.status_code = 200
        mock_resp.raise_for_status = lambda: None
        mock_resp.json = lambda: {
            "data": [
                {"embedding": [0.1] * 1024, "index": 0},
                {"embedding": [0.2] * 1024, "index": 1}
            ]
        }
        mock_post.return_value = mock_resp
        vectors = await client.embed_texts(["文本1", "文本2"])
        assert len(vectors) == 2
        assert len(vectors[0]) == 1024


def test_pgvector_store_sql_generation():
    store = PgVectorStore(db_url="postgresql://fake")
    where_sql, params = store._build_filter_clauses(
        stock_codes=["300750"],
        cognitive_layers=["GROUND_TRUTH"],
        category_paths=["GROUND_TRUTH.PERIODIC.%"]
    )
    assert "stock_codes" in where_sql
    assert "cognitive_layer" in where_sql
    assert "category_path" in where_sql
    assert len(params) >= 3
