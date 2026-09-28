from app.models.candidate import CandidateContract
from app.retrieval.ports import VectorSearchClient


class _MilvusRetriever:
    collection: str
    domain: str
    key_field: str
    name_field: str

    def __init__(self, client: VectorSearchClient) -> None:
        self._client = client

    async def search(self, text: str, top_k: int) -> list[CandidateContract]:
        hits = await self._client.search(self.collection, text, top_k)
        return [CandidateContract(
            domain=self.domain,
            key=hit[self.key_field],
            name=hit[self.name_field],
            aliases=tuple(hit.get("aliases", ())),
            supported_usage=tuple(hit.get("supported_usage", ("filter",))),
            score=float(hit.get("score", 0)),
        ) for hit in hits]


class MilvusMetricRetriever(_MilvusRetriever):
    collection = "fund_metrics"
    domain = "metric"
    key_field = "mnemonic"
    name_field = "index_name"


class MilvusScopeRetriever(_MilvusRetriever):
    collection = "fund_scopes"
    domain = "scope"
    key_field = "source_sector_id"
    name_field = "name"
