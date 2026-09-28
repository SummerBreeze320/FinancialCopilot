from __future__ import annotations

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


class ScreeningGraphNodes:
    def __init__(self, analysis: AnalysisService, builder: CommandBuilder | None = None) -> None:
        self._analysis = analysis
        self._builder = builder or CommandBuilder()

    async def query_preprocess(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        prep = preprocess(state["query"], state.get("previous_dsl"))
        intent = "EDIT" if prep.previous_dsl else "CREATE"
        complexity = "SIMPLE" if prep.edit_kind == "simple" else ("COMPLEX" if intent == "EDIT" else "NONE")
        event = _make_trace("query_preprocess", t0, {"intent": intent, "complexity": complexity})
        return {
            "normalized_query": prep.normalized_query,
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
            }
            for c in pool.candidates
        ]
        contract_index = {c["key"]: c for c in raw_pool}
        event = _make_trace("parallel_recall", t0, {"candidate_count": len(raw_pool)})
        return {
            "candidate_pool": raw_pool,
            "candidate_contract_index": contract_index,
            "trace": [*state.get("trace", []), event],
        }

    async def candidate_adjudication(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        contracts = [
            CandidateContract(
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
            )
            for c in state.get("candidate_pool", [])
        ]
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
        contracts = [
            CandidateContract(
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
            )
            for c in state.get("candidate_pool", [])
        ]
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
            c["key"]: CandidateContract(
                domain=c["domain"],
                key=c["key"],
                name=c["name"],
                supported_usage=tuple(c.get("supported_usage", ("filter",))),
            )
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
        final_dsl = state["draft_dsl"] if not state.get("validation_errors") else None
        event = _make_trace("command_finalize", t0, {"valid": final_dsl is not None})
        return {
            "final_dsl": final_dsl,
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

    async def simple_semantic_parse(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        from app.pipeline.mvp import compile_mvp_query
        dsl = compile_mvp_query(state["normalized_query"])
        event = _make_trace("simple_semantic_parse", t0, {"limit": dsl.limit})
        return {
            "draft_dsl": dsl.model_dump(by_alias=True, exclude_none=True),
            "trace": [*state.get("trace", []), event],
        }

    async def direct_build(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        candidate = state["candidate_pool"][0]
        dsl_dict = {
            "version": "fund-screening/v1",
            "operator": "AND",
            "children": [{
                "sector": {"operator": "OR", "children": [{"sectorId": self._builder._default_sector_id, "match": "SELF_OR_DESCENDANT"}]},
                "metric": {"operator": "AND", "children": [{"metric": candidate["key"], "operator": "=", "value": candidate["name"], "unit": candidate.get("value_type", "STRING")}]},
            }],
            "limit": 20,
        }
        event = _make_trace("direct_build", t0, {"candidate": candidate["key"]})
        return {
            "draft_dsl": dsl_dict,
            "trace": [*state.get("trace", []), event],
        }

