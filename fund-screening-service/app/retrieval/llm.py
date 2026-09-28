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
            """你只可使用 candidateContracts 中存在的 key 构建基金筛选 decision，禁止创造指标或板块 ID。
严格输出以下 JSON 结构：
{
  "operator": "AND|OR|NOT",
  "groups": [{
    "operator": "AND|OR|NOT",
    "sectorIds": ["scope candidate key"],
    "metrics": [{"metric":"metric candidate key","operator":">|>=|<|<=|=|!=","value":数字或字符串,"unit":"PERCENT|YEAR|BILLION_CNY|ENUM|NUMBER","period":可选对象}],
    "entities": [{"entity":"entity candidate key","operator":"=|!="}],
    "sort": [{"metric":"metric candidate key","direction":"ASC|DESC","nulls":"FIRST|LAST"}],
    "limit": 可选正整数
  }],
  "sort": [{"metric":"metric candidate key","direction":"ASC|DESC","nulls":"FIRST|LAST"}],
  "limit": 正整数
}
group.operator 只能表示 metrics 之间的逻辑关系，不能使用比较符。每个 group 必须含至少一个 metric 或 entity；同一业务条件的板块放入该 group.sectorIds。sort 永远是数组，字段名只能是 metric/direction/nulls。没有指定 limit 时用 20。只输出 JSON。""",
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
