import httpx
import pytest

from app.core.reranker import Reranker
from app.core.resolver import ScoredCandidate
from app.catalog.models import CatalogRecord
from app.models.api import Category, MatchMethod


def scored(entity_id: str, category: Category, confidence: float) -> ScoredCandidate:
    value = entity_id.split(":", 1)[1]
    return ScoredCandidate(
        entity=CatalogRecord(entity_id, category, value, (value,), value.casefold(),
                             (value.casefold(),), {}, value),
        confidence=confidence,
        method=MatchMethod.HYBRID,
    )


CANDIDATES = [
    scored("stock_code:000001.SZ", Category.STOCK_CODE, 0.80),
    scored("fund_code:000001.OF", Category.FUND_CODE, 0.79),
]


@pytest.mark.asyncio
async def test_reranker_reorders_only_supplied_candidates():
    async def handler(request):
        return httpx.Response(200, json={"choices": [{"message": {"content": (
            '{"ranked_candidates":['
            '{"entity_id":"unknown:x","relevance":1.0,"reason":"bad"},'
            '{"entity_id":"fund_code:000001.OF","relevance":0.96,"reason":"基金上下文"}]}'
        )}}]})

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://llm/v1")
    result, degraded = await Reranker(http, "test", "key").rerank("000001", "000001基金", CANDIDATES)
    assert [item.entity.id for item in result] == ["fund_code:000001.OF", "stock_code:000001.SZ"]
    assert result[0].confidence == 0.96
    assert degraded is False
    await http.aclose()


@pytest.mark.asyncio
async def test_malformed_response_falls_back_without_inventing_entities():
    async def handler(request):
        return httpx.Response(200, json={"choices": [{"message": {"content": "not-json"}}]})

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://llm/v1")
    result, degraded = await Reranker(http, "test", "key").rerank("000001", "000001", CANDIDATES)
    assert [item.entity.id for item in result] == [item.entity.id for item in CANDIDATES]
    assert degraded is True
    await http.aclose()
