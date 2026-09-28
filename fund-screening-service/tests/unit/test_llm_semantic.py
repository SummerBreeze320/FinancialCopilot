import json

import pytest

from app.pipeline.analysis import CandidatePool
from app.retrieval.llm import OpenAiSemanticLlm
from app.models.candidate import CandidateContract


class FakeResponse:
    def __init__(self, content): self._content = content
    def raise_for_status(self): pass
    def json(self): return {"choices": [{"message": {"content": json.dumps(self._content, ensure_ascii=False)}}]}


class FakeHttp:
    def __init__(self, responses): self.responses, self.requests = responses, []
    async def post(self, path, json):
        self.requests.append((path, json))
        return FakeResponse(self.responses.pop(0))


@pytest.mark.asyncio
async def test_llm_generates_plan_then_adjudicates_with_candidate_contracts() -> None:
    http = FakeHttp([
        {"conditions": [{"conditionId": "c1", "mention": "最大回撤", "domain": "metric"}]},
        {"groups": [{"metrics": [{"metric": "f_risk_maxdownside", "operator": "<", "value": 15, "unit": "PERCENT"}]}], "limit": 20},
    ])
    llm = OpenAiSemanticLlm(http, model="gpt-5.3-codex", reasoning_effort="low")
    plan = await llm.generate_plan("最大回撤低于15%")
    decision = await llm.adjudicate("最大回撤低于15%", plan, CandidatePool([
        CandidateContract("metric", "f_risk_maxdownside", "最大回撤")
    ]))

    assert plan.conditions[0].domain == "metric"
    assert decision["groups"][0]["metrics"][0]["metric"] == "f_risk_maxdownside"
    assert http.requests[1][1]["reasoning_effort"] == "low"
    assert "f_risk_maxdownside" in http.requests[1][1]["messages"][1]["content"]
