from __future__ import annotations

import json
from dataclasses import asdict
from typing import Any, Type, TypeVar

from app.models.semantic import DecisionOutput, RecallPlanOutput, validate_against_candidate_contracts
from app.pipeline.analysis import ConditionPlan, RecallPlan

T = TypeVar("T")


class OpenAiSemanticLlm:
    """通过 OpenAI 兼容接口生成 recall plan 并完成候选受限裁决。"""

    def __init__(self, http: Any, model: str, reasoning_effort: str = "low") -> None:
        self._http = http
        self._model = model
        self._reasoning_effort = reasoning_effort

    async def generate_plan(self, query: str) -> RecallPlan:
        system = "你只负责把基金筛选 query 拆成 condition。domain 只能是 metric、scope、entity。输出 JSON: {conditions:[{conditionId,mention,domain}]}。"
        payload = await self._complete_structured(system, query, RecallPlanOutput)
        conditions = tuple(
            ConditionPlan(item.condition_id, item.mention, item.domain)
            for item in payload.conditions
        )
        domains = tuple(dict.fromkeys(item.domain for item in conditions)) or tuple(payload.domains)
        return RecallPlan(domains, conditions)

    async def adjudicate(self, query: str, plan: RecallPlan, pool: Any) -> dict[str, Any]:
        candidates = [asdict(item) for item in pool.candidates]
        contract_index = {item.key: asdict(item) for item in pool.candidates}
        system = """你只可使用 candidateContracts 中存在的 key 构建基金筛选 decision，禁止创造指标或板块 ID。
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
group.operator 只能表示 metrics 之间的逻辑关系，不能使用比较符。每个 group 必须含至少一个 metric 或 entity；同一业务条件的板块放入该 group.sectorIds。sort 永远是数组，字段名只能是 metric/direction/nulls。没有指定 limit 时用 20。只输出 JSON。"""
        user_content = json.dumps({"query": query, "plan": asdict(plan), "candidateContracts": candidates}, ensure_ascii=False)
        decision_obj = await self._complete_structured(system, user_content, DecisionOutput)
        validate_against_candidate_contracts(decision_obj, contract_index)
        return decision_obj.model_dump(by_alias=True, exclude_none=True)

    async def _complete_structured(self, system: str, user: str, schema_cls: Type[T]) -> T:
        last_error = None
        for _ in range(2):
            try:
                raw_json = await self._complete_json(system, user)
                return schema_cls.model_validate(raw_json)  # type: ignore[return-value]
            except Exception as e:
                last_error = e
        raise last_error  # type: ignore[misc]

    async def _complete_json(self, system: str, user: str) -> dict[str, Any]:
        response = await self._http.post("/chat/completions", json={
            "model": self._model,
            "reasoning_effort": self._reasoning_effort,
            "response_format": {"type": "json_object"},
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        })
        response.raise_for_status()
        return json.loads(response.json()["choices"][0]["message"]["content"])
