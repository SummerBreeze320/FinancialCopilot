from __future__ import annotations

import asyncio
import json
from dataclasses import asdict
from typing import Any, Type, TypeVar

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import SystemMessage
from langchain_core.prompts import ChatPromptTemplate, HumanMessagePromptTemplate
from langchain_openai import ChatOpenAI

from app.models.semantic import DecisionOutput, RecallPlanOutput, validate_against_candidate_contracts
from app.models.intent import IntentDecision, IntentModelOutput
from app.models.semantic_plan import SemanticPlan, AdjudicationResult
from app.models.edit_plan import EditPlan
from app.pipeline.analysis import ConditionPlan, RecallPlan

T = TypeVar("T")

PLAN_SYSTEM_PROMPT = """你只负责把基金筛选 query 拆成 condition。输出单个 JSON 对象：
{
  "domains": ["metric"|"scope"|"entity"],
  "conditions": [
    {"condition_id": "c1", "mention": "...", "domain": "metric"|"scope"|"entity"}
  ]
}
只输出有效 JSON。"""

SEMANTIC_PLAN_SYSTEM_PROMPT = """你只负责将用户自然语言基金筛选请求解析为结构化 Semantic Plan。
严格规则：
1. 划分业务分组 groups，组内分别构建 scopeTree 与 metricTree 递归逻辑树。
2. 识别每个逻辑叶子的 conditionId (如 s1, m1)、mention (原始提及)、domain (scope/metric/entity)。
3. 提供 operatorHint (如 >, >=, <, <=, =)、valueHint (数值或字符串)、unitHint (如 年, 亿, %)。
4. 如果指标涉及时间周期或收益区间，必须提取 timeHint：
   - 滚动周期：type="TRAILING", value=数值, unit="MONTH"|"YEAR"|"DAY" (例如 近一年 value=1, unit="YEAR"; 近三个月 value=3, unit="MONTH")
   - 自然季度：type="CALENDAR_QUARTER", offset=0 (本季度), offset=-1 (上季度)
   - 今年以来：type="YEAR_TO_DATE"
   - 固定季度：type="FIXED_QUARTER", year=2024, quarter=2
   注：LLM 只输出时间语义，绝不计算具体日期。明确区分自然季度与滚动三个月；“季度收益”缺少限定时按当前自然季度处理 (offset=0)。
5. 提取 sortMentions 与 limit。未明确每组时排序和数量属于 ROOT。
6. 严禁生成任何指标 ID、板块 ID、实体代码或最终 DSL。只能忠实提取用户语言中的条件树结构。
"""

EDIT_PLAN_SYSTEM_PROMPT = """你只负责根据上一轮筛选条件摘要 previousPlanSummary 和用户新输入 query，输出增量编辑计划 EditPlan。
严禁生成最终 DSL 或业务 ID。只能输出结构化编辑操作：
- ADD: 增加新叶子条件 (提供 conditionId, mention, domain, operatorHint, valueHint, unitHint)
- REPLACE: 替换现有条件 (必须指定 targetConditionId, 以及新 condition)
- REMOVE: 删除条件 (必须指定 targetConditionId)
- CHANGE_OPERATOR: 修改逻辑运算符
- RESET: 清空并重新构建
未在 operations 中出现的条件将严格保持原样。
"""

CONTEXT_INTENT_SYSTEM_PROMPT = """你是基金筛选意图的上下文判定器。根据 query、previousDsl 和 pendingClarification 判断 CREATE 或 EDIT。
previousDsl 的存在本身不能作为 EDIT 证据；只有明确延续、引用或修改上一轮条件时才可选择 EDIT。不确定时选择 CREATE。
必须给出 query 中的 evidence，并输出 CREATE 与 EDIT 的概率。"""

COMPLEX_INTENT_SYSTEM_PROMPT = """你是基金筛选意图的复杂兜底判定器。根据 query、previousDsl 和 pendingClarification 判断 CREATE 或 EDIT。
previousDsl 的存在本身不能作为 EDIT 证据；只有明确延续、引用、修改、替换或删除上一轮条件时才可选择 EDIT。不确定时选择 CREATE。
必须给出 query 中的 evidence，并输出 CREATE 与 EDIT 的概率。"""

LEAF_ADJUDICATION_SYSTEM_PROMPT = """你只负责将给定 Semantic Plan 中的每个逻辑叶子 conditionId 绑定到 candidateContracts 中对应的候选 key。
严禁重构、新增、删除或修改 Plan 中的逻辑树或分组。
规则：
1. conditionBindings 中的 conditionId 必须来自输入 Plan。
2. candidateKey 必须来自对应 condition 的候选列表，严禁凭空捏造。
3. 若 candidateContract 包含 bindingRole 列表，请根据上下文选择合适的 bindingRole（例如 TRACKED_INDEX 或 BENCHMARK）。
4. 运算符 operator 必须在候选支持的 supportedOperators 中。
5. params 中的参数必须按指标 parameters 契约填写，ENUM 参数填写整数选项值。
6. 无法确定的歧义请在 clarifications 中输出带 conditionId 的结构化澄清，严禁瞎猜。
"""

ADJUDICATION_SYSTEM_PROMPT = """你只可使用 candidateContracts 中存在的 key 构建基金筛选 decision，禁止创造指标或板块 ID。
若指标有参数契约 parameters，必须在 params 对象中提供命名字段键值对（例如 {"creditRating": 1}），禁止输出位置数组或函数字符串。ENUM 参数必须按 options 中的整数 value 填写。比较符必须来自指标支持的 supportedOperators，比较值必须符合其类型与单位。
group.operator 只能表示 metrics 之间的逻辑关系，不能使用比较符。每个 group 必须含至少一个 metric 或 entity；同一业务条件的板块放入该 group.sectorIds。sort 永远是数组，字段名只能是 metric/direction/nulls。没有指定 limit 时用 20。
输出单个 JSON 对象：
{
  "operator": "AND"|"OR",
  "groups": [
    {
      "sectorIds": ["..."],
      "metrics": [{"metric": "...", "params": {"key": value}, "operator": ">"|">="|"<"|"<="|"=", "value": ..., "unit": "..."}],
      "entities": [{"entity": "...", "operator": "="}]
    }
  ],
  "sort": [{"metric": "...", "direction": "ASC"|"DESC"}],
  "limit": 20
}
只输出有效 JSON。"""


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
                SystemMessage(content=PLAN_SYSTEM_PROMPT),
                HumanMessagePromptTemplate.from_template("{query}"),
            ])
            try:
                self.plan_runnable = plan_prompt | self.chat_model.with_structured_output(RecallPlanOutput, method="json_schema")
            except (TypeError, NotImplementedError, AttributeError):
                try:
                    self.plan_runnable = plan_prompt | self.chat_model.with_structured_output(RecallPlanOutput)
                except (TypeError, NotImplementedError, AttributeError):
                    self.plan_runnable = None

            adjudicate_prompt = ChatPromptTemplate.from_messages([
                SystemMessage(content=ADJUDICATION_SYSTEM_PROMPT),
                HumanMessagePromptTemplate.from_template("{context}"),
            ])
            try:
                self.adjudicate_runnable = adjudicate_prompt | self.chat_model.with_structured_output(DecisionOutput, method="json_schema")
            except (TypeError, NotImplementedError, AttributeError):
                try:
                    self.adjudicate_runnable = adjudicate_prompt | self.chat_model.with_structured_output(DecisionOutput)
                except (TypeError, NotImplementedError, AttributeError):
                    self.adjudicate_runnable = None
        else:
            self.plan_runnable = None
            self.adjudicate_runnable = None

    @property
    def semantic_plan_runnable(self):
        if not hasattr(self, "_semantic_plan_runnable"):
            if self.chat_model is None:
                self._semantic_plan_runnable = None
            else:
                sem_plan_prompt = ChatPromptTemplate.from_messages([
                    SystemMessage(content=SEMANTIC_PLAN_SYSTEM_PROMPT),
                    HumanMessagePromptTemplate.from_template("{query}"),
                ])
                try:
                    self._semantic_plan_runnable = sem_plan_prompt | self.chat_model.with_structured_output(SemanticPlan, method="json_schema")
                except (TypeError, NotImplementedError, AttributeError):
                    try:
                        self._semantic_plan_runnable = sem_plan_prompt | self.chat_model.with_structured_output(SemanticPlan)
                    except (TypeError, NotImplementedError, AttributeError):
                        self._semantic_plan_runnable = None
        return self._semantic_plan_runnable

    @semantic_plan_runnable.setter
    def semantic_plan_runnable(self, value):
        self._semantic_plan_runnable = value

    @property
    def edit_plan_runnable(self):
        if not hasattr(self, "_edit_plan_runnable"):
            if self.chat_model is None:
                self._edit_plan_runnable = None
            else:
                edit_plan_prompt = ChatPromptTemplate.from_messages([
                    SystemMessage(content=EDIT_PLAN_SYSTEM_PROMPT),
                    HumanMessagePromptTemplate.from_template("{context}"),
                ])
                try:
                    self._edit_plan_runnable = edit_plan_prompt | self.chat_model.with_structured_output(EditPlan, method="json_schema")
                except (TypeError, NotImplementedError, AttributeError):
                    try:
                        self._edit_plan_runnable = edit_plan_prompt | self.chat_model.with_structured_output(EditPlan)
                    except (TypeError, NotImplementedError, AttributeError):
                        self._edit_plan_runnable = None
        return self._edit_plan_runnable

    @edit_plan_runnable.setter
    def edit_plan_runnable(self, value):
        self._edit_plan_runnable = value

    @property
    def leaf_adjudicate_runnable(self):
        if not hasattr(self, "_leaf_adjudicate_runnable"):
            if self.chat_model is None:
                self._leaf_adjudicate_runnable = None
            else:
                leaf_adjudicate_prompt = ChatPromptTemplate.from_messages([
                    SystemMessage(content=LEAF_ADJUDICATION_SYSTEM_PROMPT),
                    HumanMessagePromptTemplate.from_template("{context}"),
                ])
                try:
                    self._leaf_adjudicate_runnable = leaf_adjudicate_prompt | self.chat_model.with_structured_output(AdjudicationResult, method="json_schema")
                except (TypeError, NotImplementedError, AttributeError):
                    try:
                        self._leaf_adjudicate_runnable = leaf_adjudicate_prompt | self.chat_model.with_structured_output(AdjudicationResult)
                    except (TypeError, NotImplementedError, AttributeError):
                        self._leaf_adjudicate_runnable = None
        return self._leaf_adjudicate_runnable

    @leaf_adjudicate_runnable.setter
    def leaf_adjudicate_runnable(self, value):
        self._leaf_adjudicate_runnable = value

    def _intent_runnable(self, prompt_text: str):
        if self.chat_model is None:
            return None
        prompt = ChatPromptTemplate.from_messages([
            SystemMessage(content=prompt_text),
            HumanMessagePromptTemplate.from_template("{context}"),
        ])
        try:
            return prompt | self.chat_model.with_structured_output(IntentModelOutput, method="json_schema")
        except (TypeError, NotImplementedError, AttributeError):
            try:
                return prompt | self.chat_model.with_structured_output(IntentModelOutput)
            except (TypeError, NotImplementedError, AttributeError):
                return None

    async def generate_plan(self, query: str) -> RecallPlan:
        try:
            if self.plan_runnable is not None:
                payload: RecallPlanOutput = await self.plan_runnable.ainvoke({"query": query})
            else:
                payload = await self._complete_structured(PLAN_SYSTEM_PROMPT, query, RecallPlanOutput)
        except Exception:
            payload = await self._complete_structured(PLAN_SYSTEM_PROMPT, query, RecallPlanOutput)

        conditions = tuple(
            ConditionPlan(item.condition_id, item.mention, item.domain)
            for item in payload.conditions
        )
        domains = tuple(dict.fromkeys(item.domain for item in conditions)) or tuple(payload.domains)
        return RecallPlan(domains, conditions)

    async def generate_semantic_plan(self, query: str, previous_dsl: dict | None = None) -> SemanticPlan:
        try:
            if self.semantic_plan_runnable is not None:
                plan: SemanticPlan = await self.semantic_plan_runnable.ainvoke({"query": query})
            else:
                plan = await self._complete_structured(SEMANTIC_PLAN_SYSTEM_PROMPT, query, SemanticPlan)
        except Exception:
            plan = await self._complete_structured(SEMANTIC_PLAN_SYSTEM_PROMPT, query, SemanticPlan)
        return plan

    async def generate_edit_plan(self, query: str, previous_summary: dict[str, Any]) -> EditPlan:
        context = json.dumps(
            {"query": query, "previousPlanSummary": previous_summary},
            ensure_ascii=False,
        )
        try:
            if self.edit_plan_runnable is not None:
                plan: EditPlan = await self.edit_plan_runnable.ainvoke({"context": context})
            else:
                plan = await self._complete_structured(EDIT_PLAN_SYSTEM_PROMPT, context, EditPlan)
        except Exception:
            plan = await self._complete_structured(EDIT_PLAN_SYSTEM_PROMPT, context, EditPlan)
        return plan

    async def classify_context_intent(
        self, query: str, previous_dsl: dict[str, Any], clarification: dict[str, Any] | None
    ) -> IntentDecision:
        """使用同一模型完成上下文层的低成本意图判定。"""
        return await self._classify_intent(
            CONTEXT_INTENT_SYSTEM_PROMPT, "CONTEXT_MODEL", query, previous_dsl, clarification
        )

    async def classify_complex_intent(
        self, query: str, previous_dsl: dict[str, Any], clarification: dict[str, Any] | None
    ) -> IntentDecision:
        """使用同一模型完成复杂指代的最终兜底判定。"""
        return await self._classify_intent(
            COMPLEX_INTENT_SYSTEM_PROMPT, "COMPLEX_MODEL", query, previous_dsl, clarification
        )

    async def _classify_intent(
        self,
        prompt_text: str,
        source: str,
        query: str,
        previous_dsl: dict[str, Any],
        clarification: dict[str, Any] | None,
    ) -> IntentDecision:
        context = json.dumps(
            {"query": query, "previousDsl": previous_dsl, "pendingClarification": clarification},
            ensure_ascii=False,
        )
        runnable = self._intent_runnable(prompt_text)
        try:
            output = await runnable.ainvoke({"context": context}) if runnable is not None else await self._complete_structured(prompt_text, context, IntentModelOutput)
        except Exception:
            output = await self._complete_structured(prompt_text, context, IntentModelOutput)
        return IntentDecision(
            intent=output.intent,
            confidence=output.confidence,
            evidence=output.evidence,
            source=source,
            create_probability=output.create_probability,
            edit_probability=output.edit_probability,
        )

    async def adjudicate(self, query: str, plan: RecallPlan, pool: Any) -> dict[str, Any]:
        candidates = []
        contract_index = {}
        for item in pool.candidates:
            d = asdict(item) if hasattr(item, "__dataclass_fields__") else (item if isinstance(item, dict) else item.__dict__)
            if "parameters" in d and d["parameters"]:
                d["parameters"] = [
                    p.model_dump(by_alias=True) if hasattr(p, "model_dump")
                    else (asdict(p) if hasattr(p, "__dataclass_fields__") else p)
                    for p in d["parameters"]
                ]
            candidates.append(d)
            contract_index[item.key] = d
        user_content = json.dumps(
            {"query": query, "plan": asdict(plan), "candidateContracts": candidates},
            ensure_ascii=False,
        )

        try:
            if self.adjudicate_runnable is not None:
                decision_obj: DecisionOutput = await self.adjudicate_runnable.ainvoke({"context": user_content})
            else:
                decision_obj = await self._complete_structured(ADJUDICATION_SYSTEM_PROMPT, user_content, DecisionOutput)
        except Exception:
            decision_obj = await self._complete_structured(ADJUDICATION_SYSTEM_PROMPT, user_content, DecisionOutput)

        validate_against_candidate_contracts(decision_obj, contract_index)
        return decision_obj.model_dump(by_alias=True, exclude_none=True)

    async def adjudicate_candidates(
        self,
        plan: SemanticPlan,
        by_condition: dict[str, Any],
        route: str = "simple",
    ) -> AdjudicationResult:
        """只对 Semantic Plan 中的叶子 condition 节点进行候选绑定，严禁改写逻辑树。"""
        serializable_by_condition = {}
        for cid, info in by_condition.items():
            candidates = info.get("candidates", []) if isinstance(info, dict) else getattr(info, "candidates", [])
            serialized_candidates = []
            for c in candidates:
                cdict = asdict(c) if hasattr(c, "__dataclass_fields__") else (c if isinstance(c, dict) else c.__dict__)
                res_contract = cdict.get("result_contract") or cdict.get("resultContract")
                unit_val = None
                accepted_units = []
                if isinstance(res_contract, dict):
                    unit_val = res_contract.get("unit")
                    accepted_units = res_contract.get("acceptedInputUnits", [])
                elif hasattr(res_contract, "unit"):
                    unit_val = res_contract.unit
                    accepted_units = getattr(res_contract, "accepted_input_units", [])

                serialized_candidates.append({
                    "domain": cdict.get("domain"),
                    "key": cdict.get("key"),
                    "name": cdict.get("name"),
                    "aliases": list(cdict.get("aliases", ())),
                    "supported_operators": list(cdict.get("supported_operators", ())),
                    "value_type": cdict.get("value_type", "NUMBER"),
                    "binding_role": cdict.get("binding_role"),
                    "preferred_metric": cdict.get("preferred_metric"),
                    "unit": unit_val,
                    "acceptedInputUnits": accepted_units,
                    "resultContract": res_contract,
                    "score": cdict.get("score", 1.0),
                    "parameters": [
                        p.model_dump(by_alias=True) if hasattr(p, "model_dump") else p
                        for p in cdict.get("parameters", ())
                    ],
                    "bindings": list(cdict.get("bindings", ())),
                })
            serializable_by_condition[cid] = {
                "mention": info.get("mention") if isinstance(info, dict) else getattr(info, "mention", ""),
                "domain": info.get("domain") if isinstance(info, dict) else getattr(info, "domain", ""),
                "candidates": serialized_candidates,
            }

        context = json.dumps(
            {
                "route": route,
                "plan": plan.model_dump(by_alias=True),
                "byCondition": serializable_by_condition,
            },
            ensure_ascii=False,
        )

        try:
            if self.leaf_adjudicate_runnable is not None:
                result: AdjudicationResult = await self.leaf_adjudicate_runnable.ainvoke({"context": context})
            else:
                result = await self._complete_structured(LEAF_ADJUDICATION_SYSTEM_PROMPT, context, AdjudicationResult)
        except Exception:
            await asyncio.sleep(1.5)
            result = await self._complete_structured(LEAF_ADJUDICATION_SYSTEM_PROMPT, context, AdjudicationResult)

        return result

    async def _complete_structured(self, system: str, user: str, schema_cls: Type[Any]) -> Any:
        last_error = None
        for attempt in range(3):
            try:
                raw_json = await self._complete_json(system, user, schema_cls)
                return schema_cls.model_validate(raw_json)
            except Exception as e:
                last_error = e
                if attempt < 2:
                    await asyncio.sleep(2.0 * (attempt + 1))
        raise last_error

    async def _complete_json(self, system: str, user: str, schema_cls: Type[Any]) -> dict[str, Any]:
        response_format = {
            "type": "json_schema",
            "json_schema": {
                "name": schema_cls.__name__,
                "strict": True,
                "schema": schema_cls.model_json_schema(),
            },
        }
        if self._http is not None:
            response = await self._http.post("/chat/completions", json={
                "model": self._model,
                "reasoning_effort": self._reasoning_effort,
                "response_format": response_format,
                "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            })
            response.raise_for_status()
            content = response.json()["choices"][0]["message"]["content"]
        elif self.chat_model is not None:
            resp = await self.chat_model.ainvoke([("system", system), ("user", user)], response_format=response_format)
            content = resp.content
        else:
            raise RuntimeError("No HTTP client or ChatModel configured for JSON completion")

        if isinstance(content, str):
            content = content.strip()
            if content.startswith("```json"):
                content = content[7:]
            if content.startswith("```"):
                content = content[3:]
            if content.endswith("```"):
                content = content[:-3]
            return json.loads(content.strip())
        elif isinstance(content, dict):
            return content
        raise RuntimeError("Unexpected content type from LLM")
