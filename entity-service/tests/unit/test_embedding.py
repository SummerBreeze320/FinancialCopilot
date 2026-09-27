import httpx
import pytest

from app.core.embedding import EmbeddingClient, EmbeddingDimensionError


@pytest.mark.asyncio
async def test_embedding_preserves_response_index_order():
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"data": [
            {"index": 1, "embedding": [0.0, 1.0]},
            {"index": 0, "embedding": [1.0, 0.0]},
        ]})

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://embedding/v1")
    client = EmbeddingClient(http=http, model="test", dimension=2)
    assert await client.embed(["first", "second"]) == [[1.0, 0.0], [0.0, 1.0]]
    await http.aclose()


@pytest.mark.asyncio
async def test_rejects_wrong_embedding_dimension():
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"data": [{"index": 0, "embedding": [0.1, 0.2]}]})

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://embedding/v1")
    client = EmbeddingClient(http=http, model="test", dimension=3)
    with pytest.raises(EmbeddingDimensionError):
        await client.embed(["华夏成长"])
    await http.aclose()


@pytest.mark.asyncio
async def test_rejects_non_finite_embedding():
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            content=b'{"data":[{"index":0,"embedding":[NaN,0.2]}]}',
            headers={"content-type": "application/json"},
        )

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://embedding/v1")
    client = EmbeddingClient(http=http, model="test", dimension=2)
    with pytest.raises(EmbeddingDimensionError):
        await client.embed(["华夏成长"])
    await http.aclose()
