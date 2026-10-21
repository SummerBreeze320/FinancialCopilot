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
       parameters, result_contract, parameter_schema_version, contract_status, contract_updated_at,
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
        from app.catalog.loader import DEFAULT_SECTOR_PRIORITIES

        hits = await self._client.search(self.collection, text, top_k)
        return [CandidateContract(
            domain=self.domain,
            key=hit[self.key_field],
            name=hit[self.name_field],
            aliases=tuple(hit.get("aliases") or ()),
            supported_usage=tuple(hit.get("supported_usage") or ("filter",)),
            score=float(hit.get("score", 0)),
            default_priority=int(hit.get("default_priority") or DEFAULT_SECTOR_PRIORITIES.get(hit[self.key_field], 0)),
        ) for hit in hits]


class PgVectorMetricRetriever(_PgVectorRetriever):
    collection = "fund_metrics"
    domain = "metric"
    key_field = "mnemonic"
    name_field = "index_name"

    async def search(self, text: str, top_k: int) -> list[CandidateContract]:
        import json
        from app.models.parameters import MetricParameterDefinition

        hits = await self._client.search(self.collection, text, top_k)
        candidates: list[CandidateContract] = []
        for hit in hits:
            raw_params = hit.get("parameters")
            if isinstance(raw_params, str):
                try:
                    raw_params = json.loads(raw_params)
                except Exception:
                    raw_params = []
            raw_params = raw_params or []
            param_defs = tuple(
                MetricParameterDefinition.model_validate(p) if isinstance(p, dict) else p
                for p in sorted(
                    raw_params,
                    key=lambda x: x.get("position", 0) if isinstance(x, dict) else getattr(x, "position", 0),
                )
            )

            raw_res = hit.get("result_contract")
            if isinstance(raw_res, str):
                try:
                    raw_res = json.loads(raw_res)
                except Exception:
                    raw_res = {}
            raw_res = raw_res or {}

            value_type = raw_res.get("valueType") or raw_res.get("value_type") or "NUMBER"
            supported_ops = tuple(
                raw_res.get("supportedOperators")
                or raw_res.get("supported_operators")
                or (">", ">=", "<", "<=", "=", "!=")
            )

            from app.catalog.loader import DEFAULT_METRIC_PRIORITIES
            metric_priority = int(hit.get("default_priority") or DEFAULT_METRIC_PRIORITIES.get(hit[self.key_field], 0))

            candidates.append(CandidateContract(
                domain=self.domain,
                key=hit[self.key_field],
                name=hit[self.name_field],
                aliases=tuple(hit.get("aliases") or ()),
                supported_operators=supported_ops,
                value_type=value_type,
                supported_usage=tuple(hit.get("supported_usage") or ("filter",)),
                score=float(hit.get("score", 0)),
                parameters=param_defs,
                result_contract=raw_res,
                parameter_schema_version=int(hit.get("parameter_schema_version", 1)),
                contract_status=str(hit.get("contract_status", "INFERRED")),
                default_priority=metric_priority,
            ))
        return candidates


class PgVectorScopeRetriever(_PgVectorRetriever):
    collection = "fund_scopes"
    domain = "scope"
    key_field = "source_sector_id"
    name_field = "name"
