import pytest

from app.models.candidate import CandidateContract
from app.pipeline.analysis import AnalysisService, RecallPlan


class FakeRetriever:
    def __init__(self, candidate): self.candidate = candidate
    async def search(self, text, top_k): return [self.candidate]


@pytest.mark.asyncio
async def test_analysis_uses_three_retrievers_and_returns_contract_index() -> None:
    service = AnalysisService(
        lambda _: RecallPlan(("metric", "scope", "entity")),
        FakeRetriever(CandidateContract("metric", "f_risk_maxdownside", "最大回撤")),
        FakeRetriever(CandidateContract("scope", "1000022713000000", "半导体")),
        FakeRetriever(CandidateContract("entity", "manager:zhangkun", "张坤")),
    )
    pool = await service.analyze_and_recall("半导体基金最大回撤低于5%，张坤管理")
    assert pool.contract("f_risk_maxdownside").domain == "metric"
    assert pool.contract("1000022713000000").domain == "scope"
    assert pool.contract("manager:zhangkun").domain == "entity"
