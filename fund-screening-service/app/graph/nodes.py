from __future__ import annotations

import re
import time
from typing import Any


from app.graph.state import FundScreeningState, TraceEvent
from app.models.candidate import CandidateContract
from app.models.dsl import FundScreeningDsl
from app.pipeline.analysis import AnalysisService, CandidatePool, ConditionPlan, RecallPlan
from app.pipeline.builder import CommandBuilder
from app.pipeline.preprocess import preprocess
from app.pipeline.validator import validate_and_repair


def _make_trace(stage: str, started_at: float, summary: dict[str, Any], status: str = "completed") -> TraceEvent:
    return {
        "stage": stage,
        "duration_ms": round((time.perf_counter() - started_at) * 1000, 2),
        "summary": summary,
        "status": status,  # type: ignore[typeddict-item]
    }


def _candidate_to_dict(c: CandidateContract) -> dict[str, Any]:
    return {
        "domain": c.domain,
        "key": c.key,
        "name": c.name,
        "aliases": list(c.aliases),
        "supported_operators": list(c.supported_operators),
        "value_type": c.value_type,
        "supported_usage": list(c.supported_usage),
        "binding_role": c.binding_role,
        "preferred_metric": c.preferred_metric,
        "score": c.score,
        "parameters": [
            p.model_dump() if hasattr(p, "model_dump") else (p if isinstance(p, dict) else p.__dict__)
            for p in getattr(c, "parameters", ())
        ],
    }


def _dict_to_candidate(c: dict[str, Any]) -> CandidateContract:
    from app.models.parameters import MetricParameterDefinition
    params = tuple(
        MetricParameterDefinition.model_validate(p) if isinstance(p, dict) else p
        for p in c.get("parameters", ())
    )
    return CandidateContract(
        domain=c["domain"],
        key=c["key"],
        name=c["name"],
        aliases=tuple(c.get("aliases", ())),
        supported_operators=tuple(c.get("supported_operators", ())),
        value_type=c.get("value_type", "NUMBER"),
        supported_usage=tuple(c.get("supported_usage", ("filter",))),
        binding_role=c.get("binding_role"),
        preferred_metric=c.get("preferred_metric"),
        score=c.get("score", 1.0),
        parameters=params,
    )


class ScreeningGraphNodes:
    def __init__(self, analysis: AnalysisService, builder: CommandBuilder | None = None) -> None:
        self._analysis = analysis
        self._builder = builder or CommandBuilder()

    async def query_preprocess(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        prev = state.get("previous_dsl") or state.get("final_dsl") or state.get("draft_dsl")
        prep = preprocess(state["query"], prev)
        intent = "EDIT" if prev else "CREATE"
        complexity = "SIMPLE" if prep.edit_kind == "simple" else ("COMPLEX" if intent == "EDIT" else "NONE")
        event = _make_trace("query_preprocess", t0, {"intent": intent, "complexity": complexity})
        return {
            "normalized_query": prep.normalized_query,
            "previous_dsl": prev,
            "intent": intent,
            "edit_complexity": complexity,
            "trace": [*state.get("trace", []), event],
        }

    async def pre_recall_plan(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        plan = await self._analysis._make_plan(state["normalized_query"])
        plan_dict = {
            "domains": list(plan.domains),
            "conditions": [
                {"condition_id": c.condition_id, "mention": c.mention, "domain": c.domain}
                for c in plan.conditions
            ],
        }
        event = _make_trace("pre_recall_plan", t0, {"condition_count": len(plan.conditions)})
        return {
            "recall_plan": plan_dict,
            "trace": [*state.get("trace", []), event],
        }

    async def parallel_recall(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        plan_data = state.get("recall_plan") or {"domains": ["metric", "scope", "entity"], "conditions": []}
        conditions = tuple(
            ConditionPlan(c["condition_id"], c["mention"], c["domain"])
            for c in plan_data.get("conditions", [])
        )
        plan = RecallPlan(tuple(plan_data.get("domains", ())), conditions)
        pool = await self._analysis._recall(state["normalized_query"], plan)
        raw_pool = [_candidate_to_dict(c) for c in pool.candidates]
        contract_index = {c["key"]: c for c in raw_pool}
        event = _make_trace("parallel_recall", t0, {"candidate_count": len(raw_pool)})
        return {
            "candidate_pool": raw_pool,
            "candidate_contract_index": contract_index,
            "trace": [*state.get("trace", []), event],
        }

    async def candidate_adjudication(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        contracts = [_dict_to_candidate(c) for c in state.get("candidate_pool", [])]
        pool = CandidatePool(contracts)
        plan_data = state.get("recall_plan") or {"domains": ["metric"], "conditions": []}
        plan = RecallPlan(
            tuple(plan_data.get("domains", ())),
            tuple(ConditionPlan(c["condition_id"], c["mention"], c["domain"]) for c in plan_data.get("conditions", [])),
        )
        import inspect
        res = self._analysis._adjudicator(state["normalized_query"], plan, pool)
        decision = await res if inspect.isawaitable(res) else res
        event = _make_trace("candidate_adjudication", t0, {"group_count": len(decision.get("groups", []))})
        return {
            "decision": decision,
            "trace": [*state.get("trace", []), event],
        }

    async def command_build(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        contracts = [_dict_to_candidate(c) for c in state.get("candidate_pool", [])]
        keys = {c.key for c in contracts}
        if self._builder._default_sector_id not in keys:
            contracts.append(CandidateContract("scope", self._builder._default_sector_id, "全市场"))
        pool = CandidatePool(contracts)
        dsl = self._builder.build(state["decision"], pool)
        event = _make_trace("command_build", t0, {"limit": dsl.limit, "children_count": len(dsl.children)})
        return {
            "draft_dsl": dsl.model_dump(by_alias=True, exclude_none=True),
            "trace": [*state.get("trace", []), event],
        }

    async def validator(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        dsl = FundScreeningDsl.model_validate(state["draft_dsl"])
        contracts = {
            c["key"]: _dict_to_candidate(c)
            for c in state.get("candidate_pool", [])
            if c.get("domain") == "metric"
        }
        res = validate_and_repair(dsl, contracts)
        errors = [{"code": res.clarification.code, "message": res.clarification.message}] if res.clarification else []
        repairs = [{"code": r} for r in res.repairs]
        event = _make_trace("validator", t0, {"errors": len(errors), "repairs": len(repairs)})
        return {
            "draft_dsl": res.dsl.model_dump(by_alias=True, exclude_none=True),
            "validation_errors": errors,
            "repairs": [*state.get("repairs", []), *repairs],
            "repair_attempts": state.get("repair_attempts", 0) + (1 if repairs else 0),
            "trace": [*state.get("trace", []), event],
        }

    async def command_finalize(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        final_dsl = state["draft_dsl"] if not state.get("validation_errors") and not state.get("clarification") else None
        event = _make_trace("command_finalize", t0, {"valid": final_dsl is not None})
        res: dict[str, Any] = {
            "final_dsl": final_dsl,
            "trace": [*state.get("trace", []), event],
        }
        if final_dsl is not None:
            res["previous_dsl"] = final_dsl
        return res

    async def pre_route_recall(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        query = state.get("normalized_query") or state.get("query", "")
        # 如果是简单编辑，直接跳过召回
        if state.get("intent") == "EDIT" and state.get("edit_complexity") == "SIMPLE" and state.get("previous_dsl"):
            event = _make_trace("pre_route_recall", t0, {"candidate_count": 0, "skipped": True})
            return {
                "candidate_pool": [],
                "candidate_contract_index": {},
                "trace": [*state.get("trace", []), event],
            }

        mentions = [c.strip() for c in re.split(r"[且与和、,，\s]+", query) if c.strip()]
        if len(mentions) > 1:
            conditions = tuple(
                ConditionPlan(f"c{i+1}", m, domain)
                for i, m in enumerate(mentions)
                for domain in ("entity", "scope", "metric")
            )
            plan = RecallPlan(("entity", "scope", "metric"), conditions)
        else:
            plan = RecallPlan(("entity", "scope", "metric"))

        pool = await self._analysis._recall(query, plan)
        raw_pool = [
            {
                "domain": c.domain,
                "key": c.key,
                "name": c.name,
                "aliases": list(c.aliases),
                "supported_operators": list(c.supported_operators),
                "value_type": c.value_type,
                "supported_usage": list(c.supported_usage),
                "binding_role": c.binding_role,
                "preferred_metric": c.preferred_metric,
                "score": c.score,
                "parameters": [
                    p.model_dump() if hasattr(p, "model_dump") else p
                    for p in getattr(c, "parameters", ())
                ],
            }
            for c in pool.candidates
        ]
        contract_index = {c["key"]: c for c in raw_pool}
        event = _make_trace("pre_route_recall", t0, {"candidate_count": len(raw_pool)})
        return {
            "candidate_pool": raw_pool,
            "candidate_contract_index": contract_index,
            "trace": [*state.get("trace", []), event],
        }

    async def route_classifier(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        from app.graph.router import classify_screening_route
        route = classify_screening_route(state)
        event = _make_trace("route_classifier", t0, {"route": route})
        return {
            "route": route,
            "trace": [*state.get("trace", []), event],
        }

    async def simple_edit(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        from app.pipeline.editor import apply_simple_edit
        previous_dsl_raw = state.get("previous_dsl")
        if not previous_dsl_raw:
            raise ValueError("simple_edit requires previous_dsl")
        previous_dsl = FundScreeningDsl.model_validate(previous_dsl_raw)
        edited_dsl = apply_simple_edit(previous_dsl, state["normalized_query"])
        event = _make_trace("simple_edit", t0, {"limit": edited_dsl.limit})
        return {
            "draft_dsl": edited_dsl.model_dump(by_alias=True, exclude_none=True),
            "trace": [*state.get("trace", []), event],
        }

    async def simple_semantic_parse(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        query = state["normalized_query"]
        contracts = [_dict_to_candidate(c) for c in state.get("candidate_pool", [])]
        keys = {c.key for c in contracts}
        if self._builder._default_sector_id not in keys:
            contracts.append(CandidateContract("scope", self._builder._default_sector_id, "全市场"))
        from app.catalog.defaults import DEFAULT_METRICS
        for dm in DEFAULT_METRICS:
            if dm.key not in keys:
                contracts.append(dm)
        pool = CandidatePool(contracts)


        decision = None
        if self._analysis._adjudicator is not None:
            import inspect
            plan = RecallPlan(("metric", "scope", "entity"))
            try:
                res = self._analysis._adjudicator(query, plan, pool)
                res_decision = await res if inspect.isawaitable(res) else res
                if res_decision and res_decision.get("groups"):
                    decision = res_decision
            except Exception:
                decision = None

        if decision is None:
            decision = _build_decision_from_contracts(query, pool, self._builder._default_sector_id)

        dsl = self._builder.build(decision, pool)
        event = _make_trace("simple_semantic_parse", t0, {"limit": dsl.limit})
        return {
            "draft_dsl": dsl.model_dump(by_alias=True, exclude_none=True),
            "trace": [*state.get("trace", []), event],
        }

    async def direct_build(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        pool = state.get("candidate_pool") or []
        if not pool or pool[0].get("score", 0.0) < 0.95:
            raise ValueError("Direct routing requires a high-confidence exact candidate in candidate_pool")
        candidate = pool[0]

        if candidate.get("domain") == "entity" and candidate.get("preferred_metric"):
            metric_key = candidate["preferred_metric"]
            metric_val = candidate["key"]
            unit = candidate.get("value_type", "ENTITY_REF")
        else:
            metric_key = candidate["key"]
            metric_val = candidate["name"]
            unit = candidate.get("value_type", "STRING")

        dsl_dict = {
            "version": "fund-screening/v1",
            "operator": "AND",
            "children": [{
                "sector": {"operator": "OR", "children": [{"sectorId": self._builder._default_sector_id, "match": "SELF_OR_DESCENDANT"}]},
                "metric": {"operator": "AND", "children": [{"metric": metric_key, "operator": "=", "value": metric_val, "unit": unit}]},
            }],
            "limit": 20,
        }
        event = _make_trace("direct_build", t0, {"candidate": candidate["key"]})
        return {
            "draft_dsl": dsl_dict,
            "trace": [*state.get("trace", []), event],
        }


def _extract_op_and_val(query: str, mention: str, value_type: str) -> tuple[str | None, Any | None]:
    pattern = rf"{re.escape(mention)}\s*(大于等于|高于等于|>=|不超过|<=|小于等于|低于等于|大于|高于|超过|>|小于|低于|<|=|等于)?\s*(-?\d+(?:\.\d+)?)\s*(%|亿|年)?"
    m = re.search(pattern, query)
    op_map = {
        ">=": ">=", "大于等于": ">=", "高于等于": ">=",
        "<=": "<=", "小于等于": "<=", "低于等于": "<=", "不超过": "<=",
        ">": ">", "大于": ">", "高于": ">", "超过": ">",
        "<": "<", "小于": "<", "低于": "<",
        "=": "=", "等于": "=",
    }
    if m and m.group(2):
        raw_op = m.group(1) or ">"
        num_str = m.group(2)
        val = float(num_str) if "." in num_str else int(num_str)
        return op_map.get(raw_op, ">"), val

    m2 = re.search(rf"{re.escape(mention)}[^\d]{{0,15}}?(大于等于|高于等于|>=|不超过|<=|小于等于|低于等于|大于|高于|超过|>|小于|低于|<|=|等于)\s*(-?\d+(?:\.\d+)?)\s*(%|亿|年)?", query)
    if m2 and m2.group(2):
        raw_op = m2.group(1) or ">"
        num_str = m2.group(2)
        val = float(num_str) if "." in num_str else int(num_str)
        return op_map.get(raw_op, ">"), val

    m3 = re.search(rf"{re.escape(mention)}[^\d]{{0,15}}?(-?\d+(?:\.\d+)?)\s*(%|亿|年)?", query)
    if m3 and m3.group(1):
        num_str = m3.group(1)
        val = float(num_str) if "." in num_str else int(num_str)
        return ">", val

    return None, None


def _build_decision_from_contracts(query: str, pool: CandidatePool, default_sector_id: str) -> dict[str, Any]:
    matched_sectors = []
    for c in pool.candidates:
        if c.domain == "scope" and c.key != default_sector_id:
            if c.name in query or any(a in query for a in c.aliases):
                matched_sectors.append(c.key)
    sector_ids = matched_sectors if matched_sectors else [default_sector_id]

    metrics = []
    for c in pool.candidates:
        if c.domain != "metric":
            continue
        all_names = [c.name, *c.aliases]
        matched_name = next((name for name in all_names if name in query), None)
        if not matched_name:
            continue

        op, val = _extract_op_and_val(query, matched_name, c.value_type)
        if op is not None and val is not None:
            metrics.append({
                "metric": c.key,
                "operator": op,
                "value": val,
                "unit": c.value_type,
            })

    entities = []
    for c in pool.candidates:
        if c.domain != "entity":
            continue
        if c.name in query or any(a in query for a in c.aliases):
            entities.append({
                "entity": c.key,
                "operator": "=",
            })

    sort = []
    sort_match = re.search(r"按\s*([^，,。\s]+?)\s*(降序|升序)", query)
    sort_metric_phrase = sort_match.group(1) if sort_match else None
    sort_dir = "DESC" if (sort_match and sort_match.group(2) == "降序") else ("ASC" if (sort_match and sort_match.group(2) == "升序") else "DESC")

    if not sort_match:
        sort_match2 = re.search(r"([^，,。\s]+?)\s*(降序|升序)", query)
        if sort_match2:
            sort_metric_phrase = sort_match2.group(1)
            sort_dir = "DESC" if sort_match2.group(2) == "降序" else "ASC"

    if sort_metric_phrase:
        for c in pool.candidates:
            if c.domain != "metric" or "sort" not in c.supported_usage:
                continue
            all_names = [c.name, *c.aliases]
            if any(name in sort_metric_phrase for name in all_names):
                sort.append({"metric": c.key, "direction": sort_dir, "nulls": "LAST"})
                break

    limit_match = re.search(r"前\s*(\d+)\s*只?", query)
    limit = int(limit_match.group(1)) if limit_match else 20

    return {
        "operator": "AND",
        "groups": [{
            "sectorIds": sector_ids,
            "metrics": metrics,
            "entities": entities,
        }],
        "sort": sort,
        "limit": limit,
    }


