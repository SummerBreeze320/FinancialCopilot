from __future__ import annotations

import json
from dataclasses import asdict
from typing import Any, Type, TypeVar

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.prompts import ChatPromptTemplate
from langchain_openai import ChatOpenAI

from app.models.semantic import DecisionOutput, RecallPlanOutput, validate_against_candidate_contracts
from app.pipeline.analysis import ConditionPlan, RecallPlan

T = TypeVar("T")

PLAN_SYSTEM_PROMPT = "你只负责把基金筛选 query 拆成 condition。domain 只能是 metric、scope、entity。输出结构化 JSON。"
ADJUDICATION_SYSTEM_PROMPT = """你只可使用 candidateContracts 中存在的 key 构建基金筛选 decision，禁止创造指标或板块 ID。
group.operator 只能表示 metrics 之间的逻辑关系，不能使用比较符。每个 group 必须含至少一个 metric 或 entity；同一业务条件的板块放入该 group.sectorIds。sort 永远是数组，字段名只能是 metric/direction/nulls。没有指定 limit 时用 20。"""


class OpenAiSemanticLlm:
    """通过 LangChain 结构化输出生成 recall plan 并完成候选受限裁决。"""

    def __init__(
        self,
        http: Any = None,
        model: str = "gpt-5.6-terra",
        reasoning_effort: str = "low",
        chat_model: BaseChatModel | None = None,
        base_url: str | None = None,
        api_key: str | None = None,
    ) -> None:
        self._http = http
        self._model = model
        self._reasoning_effort = reasoning_effort

        if chat_model is not None:
            self.chat_model = chat_model
        elif base_url or (http is not None and hasattr(http, "base_url") and str(http.base_url)):
            b_url = str(base_url or http.base_url)
            a_key = api_key or "local"
            if http is not None and hasattr(http, "headers") and "Authorization" in http.headers:
                auth = http.headers["Authorization"]
                if auth.startswith("Bearer "):
                    a_key = auth[7:]
            self.chat_model = ChatOpenAI(
                base_url=b_url,
                api_key=a_key,
                model=model,
                reasoning_effort=reasoning_effort if reasoning_effort in ("low", "medium", "high") else None,
            )
        else:
            self.chat_model = None

        if self.chat_model is not None:
            plan_prompt = ChatPromptTemplate.from_messages([
                ("system", PLAN_SYSTEM_PROMPT),
                ("user", "{query}"),
            ])
            self.plan_runnable = plan_prompt | self.chat_model.with_structured_output(RecallPlanOutput)

            adjudicate_prompt = ChatPromptTemplate.from_messages([
                ("system", ADJUDICATION_SYSTEM_PROMPT),
                ("user", "{context}"),
            ])
            self.adjudicate_runnable = adjudicate_prompt | self.chat_model.with_structured_output(DecisionOutput)
        else:
            self.plan_runnable = None
            self.adjudicate_runnable = None

    async def generate_plan(self, query: str) -> RecallPlan:
        if self.plan_runnable is not None:
            payload: RecallPlanOutput = await self.plan_runnable.ainvoke({"query": query})
        else:
            payload = await self._complete_structured(PLAN_SYSTEM_PROMPT, query, RecallPlanOutput)

        conditions = tuple(
            ConditionPlan(item.condition_id, item.mention, item.domain)
            for item in payload.conditions
        )
        domains = tuple(dict.fromkeys(item.domain for item in conditions)) or tuple(payload.domains)
        return RecallPlan(domains, conditions)

    async def adjudicate(self, query: str, plan: RecallPlan, pool: Any) -> dict[str, Any]:
        candidates = [asdict(item) for item in pool.candidates]
        contract_index = {item.key: asdict(item) for item in pool.candidates}
        user_content = json.dumps(
            {"query": query, "plan": asdict(plan), "candidateContracts": candidates},
            ensure_ascii=False,
        )

        if self.adjudicate_runnable is not None:
            decision_obj: DecisionOutput = await self.adjudicate_runnable.ainvoke({"context": user_content})
        else:
            decision_obj = await self._complete_structured(ADJUDICATION_SYSTEM_PROMPT, user_content, DecisionOutput)

        validate_against_candidate_contracts(decision_obj, contract_index)
        return decision_obj.model_dump(by_alias=True, exclude_none=True)

    async def _complete_structured(self, system: str, user: str, schema_cls: Type[Any]) -> Any:
        last_error = None
        for _ in range(2):
            try:
                raw_json = await self._complete_json(system, user)
                return schema_cls.model_validate(raw_json)
            except Exception as e:
                last_error = e
        raise last_error

    async def _complete_json(self, system: str, user: str) -> dict[str, Any]:
        response = await self._http.post("/chat/completions", json={
            "model": self._model,
            "reasoning_effort": self._reasoning_effort,
            "response_format": {"type": "json_object"},
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        })
        response.raise_for_status()
        return json.loads(response.json()["choices"][0]["message"]["content"])
