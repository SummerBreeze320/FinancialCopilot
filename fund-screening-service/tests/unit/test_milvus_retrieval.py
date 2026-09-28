import pytest

from app.retrieval.milvus import MilvusMetricRetriever, MilvusScopeRetriever


class FakeMilvus:
    async def search(self, collection, text, top_k):
        if collection == "fund_metrics":
            return [{"score": 0.96, "mnemonic": "f_risk_maxdownside", "index_name": "最大回撤", "supported_usage": ["filter", "sort"]}]
        return [{"score": 0.94, "source_sector_id": "1000022713000000", "name": "半导体行业主题基金", "aliases": ["半导体"]}]


@pytest.mark.asyncio
async def test_milvus_retrievers_map_metric_and_scope_hits_to_contracts() -> None:
    client = FakeMilvus()
    metric = await MilvusMetricRetriever(client).search("最大回撤", 3)
    scope = await MilvusScopeRetriever(client).search("半导体", 3)

    assert (metric[0].domain, metric[0].key) == ("metric", "f_risk_maxdownside")
    assert (scope[0].domain, scope[0].key) == ("scope", "1000022713000000")
