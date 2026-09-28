from app.models.candidate import CandidateContract


class LazyAsyncpgPool:
    """延迟建立连接池，使应用启动不依赖数据库瞬时可用。"""

    def __init__(self, database_url: str) -> None:
        self._database_url = database_url
        self._pool = None

    async def fetch(self, sql: str, *args):
        if self._pool is None:
            import asyncpg
            self._pool = await asyncpg.create_pool(self._database_url)
        return await self._pool.fetch(sql, *args)


class PgVectorSearchClient:
    """使用现有 PostgreSQL pgvector 表执行余弦 ANN 召回。"""

    def __init__(self, pool, embedding) -> None:
        self._pool = pool
        self._embedding = embedding

    async def search(self, collection: str, text: str, top_k: int) -> list[dict]:
        vector = await self._embedding.embed(text)
        vector_literal = "[" + ",".join(str(value) for value in vector) + "]"
        sql = _METRIC_SQL if collection == "fund_metrics" else _SCOPE_SQL
        rows = await self._pool.fetch(sql, vector_literal, top_k)
        return [dict(row) for row in rows]


_METRIC_SQL = """
SELECT mnemonic, index_name, aliases, supported_usage,
       1 - (embedding <=> $1::vector) AS score
FROM rag_fund_metric
WHERE enabled = TRUE AND embedding IS NOT NULL
ORDER BY embedding <=> $1::vector
LIMIT $2
"""

_SCOPE_SQL = """
SELECT sector_id AS source_sector_id, name, aliases,
       1 - (embedding <=> $1::vector) AS score
FROM rag_fund_sector
WHERE enabled = TRUE AND embedding IS NOT NULL
ORDER BY embedding <=> $1::vector
LIMIT $2
"""


class _PgVectorRetriever:
    collection: str
    domain: str
    key_field: str
    name_field: str

    def __init__(self, client: PgVectorSearchClient) -> None:
        self._client = client

    async def search(self, text: str, top_k: int) -> list[CandidateContract]:
        hits = await self._client.search(self.collection, text, top_k)
        return [CandidateContract(
            domain=self.domain,
            key=hit[self.key_field],
            name=hit[self.name_field],
            aliases=tuple(hit.get("aliases") or ()),
            supported_usage=tuple(hit.get("supported_usage") or ("filter",)),
            score=float(hit.get("score", 0)),
        ) for hit in hits]


class PgVectorMetricRetriever(_PgVectorRetriever):
    collection = "fund_metrics"
    domain = "metric"
    key_field = "mnemonic"
    name_field = "index_name"


class PgVectorScopeRetriever(_PgVectorRetriever):
    collection = "fund_scopes"
    domain = "scope"
    key_field = "source_sector_id"
    name_field = "name"
