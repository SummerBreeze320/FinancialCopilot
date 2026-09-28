import pytest

from app.retrieval.pgvector import PgVectorMetricRetriever, PgVectorScopeRetriever, PgVectorSearchClient


class FakeEmbedding:
    async def embed(self, text): return [0.1, 0.2]


class FakePool:
    def __init__(self): self.calls = []
    async def fetch(self, sql, vector, top_k):
        self.calls.append((sql, vector, top_k))
        if "rag_fund_metric" in sql:
            return [{"mnemonic":"f_return_3y","index_name":"近3年回报","aliases":[],"supported_usage":["filter","sort"],"score":0.95}]
        return [{"source_sector_id":"1000002533000000","name":"股票型开放式基金","aliases":[],"score":0.93}]


@pytest.mark.asyncio
async def test_pgvector_searches_existing_metric_and_scope_tables() -> None:
    pool = FakePool()
    client = PgVectorSearchClient(pool, FakeEmbedding())
    metric = await PgVectorMetricRetriever(client).search("近三年收益", 3)
    scope = await PgVectorScopeRetriever(client).search("主动权益", 3)
    assert metric[0].key == "f_return_3y"
    assert scope[0].key == "1000002533000000"
    assert "embedding <=> $1::vector" in pool.calls[0][0]
