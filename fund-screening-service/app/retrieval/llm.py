import json
from dataclasses import asdict

from app.pipeline.analysis import ConditionPlan, RecallPlan


class OpenAiSemanticLlm:
    """通过 OpenAI 兼容接口生成 recall plan 并完成候选受限裁决。"""

    def __init__(self, http, model: str, reasoning_effort: str = "low") -> None:
        self._http = http
        self._model = model
        self._reasoning_effort = reasoning_effort

    async def generate_plan(self, query: str) -> RecallPlan:
        payload = await self._complete_json(
            "你只负责把基金筛选 query 拆成 condition。domain 只能是 metric、scope、entity。输出 JSON: {conditions:[{conditionId,mention,domain}]}。",
            query,
        )
        conditions = tuple(ConditionPlan(item["conditionId"], item["mention"], item["domain"]) for item in payload["conditions"])
        return RecallPlan(tuple(dict.fromkeys(item.domain for item in conditions)), conditions)

    async def adjudicate(self, query: str, plan: RecallPlan, pool) -> dict:
        candidates = [asdict(item) for item in pool.candidates]
        return await self._complete_json(
            "你只可使用 candidateContracts 中存在的 key 构建 decision。输出 groups、operator、sort、limit；禁止创造指标或板块 ID。",
            json.dumps({"query": query, "plan": asdict(plan), "candidateContracts": candidates}, ensure_ascii=False),
        )

    async def _complete_json(self, system: str, user: str) -> dict:
        response = await self._http.post("/chat/completions", json={
            "model": self._model,
            "reasoning_effort": self._reasoning_effort,
            "response_format": {"type": "json_object"},
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        })
        response.raise_for_status()
        return json.loads(response.json()["choices"][0]["message"]["content"])
