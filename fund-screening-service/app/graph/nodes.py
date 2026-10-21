import inspect
import re
import time
from typing import Any

from app.graph.direct_probe import probe_strict_direct
from app.graph.router import classify_plan_route
from app.graph.state import FundScreeningState, TraceEvent
from app.models.candidate import CandidateContract
from app.models.dsl import FundScreeningDsl
from app.models.edit_plan import EditPlan, EditOperation
from app.models.semantic_plan import (
    AdjudicationResult,
    SemanticPlan,
    PlanGroup,
    MetricTree,
    ScopeTree,
    MetricLeaf,
    ScopeLeaf,
    SortMention,
)
from app.pipeline.analysis import AnalysisService, CandidatePool, ConditionPlan, RecallPlan
from app.pipeline.builder import CommandBuilder
from app.pipeline.preprocess import preprocess
from app.pipeline.editor import apply_edit_plan, classify_edit, summarize_plan_for_edit
from app.pipeline.intent_funnel import IntentFunnel
from app.pipeline.metric_resolution import MetricResolutionPolicy
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
        "result_contract": getattr(c, "result_contract", {}) or {},
        "parameter_schema_version": getattr(c, "parameter_schema_version", 1),
        "contract_status": getattr(c, "contract_status", "INFERRED"),
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
        result_contract=c.get("result_contract", {}),
        parameter_schema_version=c.get("parameter_schema_version", 1),
        contract_status=c.get("contract_status", "INFERRED"),
    )


def _reconstruct_semantic_plan_from_dsl(dsl_dict: dict[str, Any]) -> SemanticPlan:
    groups = []
    sort_mentions = []
    limit = dsl_dict.get("limit", 20)

    for idx, g in enumerate(dsl_dict.get("children", [])):
        gid = f"g{idx+1}"
        s_leaves = []
        m_leaves = []

        sector_tree = g.get("sector")
        if sector_tree:
            for s_idx, s in enumerate(sector_tree.get("children", [])):
                cid = f"s_{idx+1}_{s_idx+1}"
                mention = s.get("sectorId") or s.get("sector_id") or ""
                s_leaves.append(ScopeLeaf(conditionId=cid, mention=mention))

        metric_tree = g.get("metric")
        if metric_tree:
            def _extract_metric_preds(node: dict[str, Any]):
                if "metric" in node and node["metric"]:
                    cid = f"m_{idx+1}_{len(m_leaves)+1}"
                    m_leaves.append(
                        MetricLeaf(
                            conditionId=cid,
                            mention=node["metric"],
                            operatorHint=node.get("operator", "="),
                            valueHint=node.get("value"),
                            unitHint=node.get("unit"),
                        )
                    )
                for ch in node.get("children", []):
                    _extract_metric_preds(ch)

            _extract_metric_preds(metric_tree)

        groups.append(
            PlanGroup(
                groupId=gid,
                scopeTree=ScopeTree(operator="OR", children=s_leaves),
                metricTree=MetricTree(operator="AND", children=m_leaves),
            )
        )

    for s in dsl_dict.get("sort", []):
        sort_mentions.append(SortMention(mention=s.get("metric", ""), direction=s.get("direction", "DESC")))

    return SemanticPlan(
        intent="EDIT",
        groups=groups,
        sort_mentions=sort_mentions,
        limit=limit,
    )


class ScreeningGraphNodes:
    def __init__(
        self,
        analysis: AnalysisService,
        builder: CommandBuilder | None = None,
        candidates_catalog: list[CandidateContract] | None = None,
        intent_funnel: IntentFunnel | None = None,
    ) -> None:
        self._analysis = analysis
        self._builder = builder or CommandBuilder()
        self._candidates_catalog = candidates_catalog or []
        self._intent_funnel = intent_funnel or IntentFunnel()

    async def query_preprocess(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        prev = state.get("previous_dsl") or state.get("final_dsl") or state.get("draft_dsl")
        prep = preprocess(state["query"], prev)
        decision = await self._intent_funnel.classify(
            prep.normalized_query, prev, state.get("clarification")
        )
        edit_kind = classify_edit(prep.normalized_query, prev)
        intent = decision.intent
        complexity = "SIMPLE" if intent == "EDIT" and edit_kind.value == "simple" else ("COMPLEX" if intent == "EDIT" else "NONE")
        event = _make_trace(
            "query_preprocess", t0,
            {"intent": intent, "complexity": complexity, "source": decision.source, "reasonCodes": decision.reason_codes},
        )
        return {
            "normalized_query": prep.normalized_query,
            "previous_dsl": prev,
            "intent": intent,
            "intent_decision": decision.model_dump(by_alias=True, exclude_none=True),
            "edit_complexity": complexity,
            "trace": [*state.get("trace", []), event],
        }

    async def strict_direct_probe(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        query = state.get("normalized_query") or state.get("query", "")
        if state.get("intent") == "EDIT" and state.get("edit_complexity") == "SIMPLE" and state.get("previous_dsl"):
            event = _make_trace("strict_direct_probe", t0, {"route": "simple_edit", "reason": "SIMPLE_EDIT"})
            return {
                "route": "simple_edit",
                "route_reason": "SIMPLE_EDIT",
                "trace": [*state.get("trace", []), event],
            }

        is_direct, direct_dsl, reason = probe_strict_direct(
            query,
            self._candidates_catalog,
            previous_dsl=state.get("previous_dsl"),
        )
        if is_direct and direct_dsl is not None:
            event = _make_trace("strict_direct_probe", t0, {"route": "direct", "reason": reason})
            return {
                "route": "direct",
                "route_reason": reason,
                "draft_dsl": direct_dsl.model_dump(by_alias=True, exclude_none=True),
                "trace": [*state.get("trace", []), event],
            }

        event = _make_trace("strict_direct_probe", t0, {"route": "semantic_plan", "reason": reason})
        return {
            "route": "semantic_plan",
            "route_reason": reason,
            "trace": [*state.get("trace", []), event],
        }

    async def semantic_plan(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        query = state["normalized_query"]
        intent = state.get("intent")
        complexity = state.get("edit_complexity")

        # EDIT COMPLEX with previous DSL / Plan
        if intent == "EDIT" and complexity == "COMPLEX" and hasattr(self._analysis, "generate_edit_plan"):
            prev_plan_raw = state.get("semantic_plan")
            if prev_plan_raw:
                prev_plan = SemanticPlan.model_validate(prev_plan_raw) if isinstance(prev_plan_raw, dict) else prev_plan_raw
            else:
                prev_dsl = state.get("previous_dsl")
                prev_plan = _reconstruct_semantic_plan_from_dsl(prev_dsl) if prev_dsl else SemanticPlan(intent="CREATE", groups=[])

            summary = summarize_plan_for_edit(prev_plan)
            res = self._analysis.generate_edit_plan(query, summary)
            edit_plan = await res if inspect.isawaitable(res) else res
            new_plan, recall_ids = apply_edit_plan(prev_plan, edit_plan)

            event = _make_trace("semantic_plan", t0, {"mode": "edit_plan", "operations": len(edit_plan.operations), "recallConditions": len(recall_ids)})
            return {
                "semantic_plan": new_plan.model_dump(by_alias=True, exclude_none=True),
                "edit_plan": edit_plan.model_dump(by_alias=True, exclude_none=True),
                "conditions_to_recall": recall_ids,
                "llm_calls": state.get("llm_calls", 0) + 1,
                "trace": [*state.get("trace", []), event],
            }

        if hasattr(self._analysis, "generate_semantic_plan"):
            res = self._analysis.generate_semantic_plan(query, state.get("previous_dsl"))
            plan = await res if inspect.isawaitable(res) else res
        else:
            res = self._analysis._make_plan(query)
            plan = await res if inspect.isawaitable(res) else res

        if isinstance(plan, RecallPlan) or not hasattr(plan, "groups"):
            plan_dict = {
                "domains": list(plan.domains) if hasattr(plan, "domains") else ["metric", "scope", "entity"],
                "conditions": [
                    {"condition_id": c.condition_id, "mention": c.mention, "domain": c.domain}
                    for c in getattr(plan, "conditions", [])
                ],
            }
            event = _make_trace("pre_recall_plan", t0, {"condition_count": len(getattr(plan, "conditions", []))})
            return {
                "recall_plan": plan_dict,
                "llm_calls": state.get("llm_calls", 0) + 1,
                "trace": [*state.get("trace", []), event],
            }

        extra_llm_calls = 0
        if isinstance(plan, SemanticPlan):
            from app.pipeline.semantic_coverage import check_semantic_coverage
            cov = check_semantic_coverage(query, plan)
            if not cov.is_valid and hasattr(self._analysis, "generate_semantic_plan"):
                missing = ", ".join(cov.missing_fragments) if cov.missing_fragments else "部分数值条件"
                retry_prompt = f"{query}\n[系统提示：上一次解析遗漏了以下条件，请务必完整保留所有条件且严禁填0占位：{missing}]"
                try:
                    retry_res = self._analysis.generate_semantic_plan(retry_prompt, state.get("previous_dsl"))
                    retry_plan = await retry_res if inspect.isawaitable(retry_res) else retry_res
                    if isinstance(retry_plan, SemanticPlan):
                        extra_llm_calls = 1
                        retry_cov = check_semantic_coverage(query, retry_plan)
                        if retry_cov.is_valid or len(retry_cov.missing_fragments) < len(cov.missing_fragments):
                            plan = retry_plan
                except Exception:
                    pass

        plan_dict = plan.model_dump(by_alias=True, exclude_none=True) if hasattr(plan, "model_dump") else plan
        event = _make_trace("semantic_plan", t0, {"groups": len(getattr(plan, "groups", []))})
        return {
            "semantic_plan": plan_dict,
            "llm_calls": state.get("llm_calls", 0) + 1 + extra_llm_calls,
            "trace": [*state.get("trace", []), event],
        }

    async def recall_by_condition(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        if not state.get("semantic_plan") and state.get("recall_plan"):
            return await self.parallel_recall(state)

        plan_raw = state.get("semantic_plan") or {}
        plan = SemanticPlan.model_validate(plan_raw) if isinstance(plan_raw, dict) else plan_raw
        target_condition_ids = state.get("conditions_to_recall")

        if hasattr(self._analysis, "recall_by_condition"):
            try:
                res = self._analysis.recall_by_condition(plan, target_condition_ids=target_condition_ids)
            except TypeError:
                res = self._analysis.recall_by_condition(plan)
            by_condition, contract_index = await res if inspect.isawaitable(res) else res
        else:
            by_condition, contract_index = {}, {}

        serialized_by_cond = dict(state.get("by_condition") or {})
        for cid, info in by_condition.items():
            candidates = info.get("candidates", []) if isinstance(info, dict) else getattr(info, "candidates", [])
            serialized_by_cond[cid] = {
                "mention": info.get("mention") if isinstance(info, dict) else getattr(info, "mention", ""),
                "domain": info.get("domain") if isinstance(info, dict) else getattr(info, "domain", ""),
                "candidates": [_candidate_to_dict(c) for c in candidates],
            }

        raw_contract_index = dict(state.get("candidate_contract_index") or {})
        for k, c in contract_index.items():
            raw_contract_index[k] = _candidate_to_dict(c) if isinstance(c, CandidateContract) else c

        all_candidates = list(raw_contract_index.values())
        event = _make_trace("recall_by_condition", t0, {"conditions": len(serialized_by_cond), "candidates": len(all_candidates)})
        return {
            "by_condition": serialized_by_cond,
            "candidate_contract_index": raw_contract_index,
            "candidate_pool": all_candidates,
            "trace": [*state.get("trace", []), event],
        }

    async def simple_full_router(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        if not state.get("semantic_plan") and state.get("recall_plan"):
            from app.graph.router import classify_screening_route
            route = classify_screening_route(state)
            event = _make_trace("simple_full_router", t0, {"route": route, "reason": "LEGACY_ROUTE"})
            return {
                "route": route,
                "route_reason": "LEGACY_ROUTE",
                "trace": [*state.get("trace", []), event],
            }

        plan_raw = state.get("semantic_plan") or {}
        plan = SemanticPlan.model_validate(plan_raw) if isinstance(plan_raw, dict) else plan_raw
        by_condition = state.get("by_condition") or {}
        route, reason = classify_plan_route(plan, by_condition)
        event = _make_trace("simple_full_router", t0, {"route": route, "reason": reason})
        return {
            "route": route,
            "route_reason": reason,
            "trace": [*state.get("trace", []), event],
        }

    async def simple_adjudication(self, state: FundScreeningState) -> dict[str, Any]:
        return await self._run_adjudication(state, route="simple")

    async def full_adjudication(self, state: FundScreeningState) -> dict[str, Any]:
        return await self._run_adjudication(state, route="full")

    async def _run_adjudication(self, state: FundScreeningState, route: str) -> dict[str, Any]:
        t0 = time.perf_counter()
        if not state.get("semantic_plan") and state.get("recall_plan"):
            if route == "simple":
                return await self.simple_semantic_parse(state)
            else:
                return await self.candidate_adjudication(state)

        plan_raw = state.get("semantic_plan") or {}
        plan = SemanticPlan.model_validate(plan_raw) if isinstance(plan_raw, dict) else plan_raw
        by_condition = state.get("by_condition") or {}

        if hasattr(self._analysis, "adjudicate_candidates"):
            res = self._analysis.adjudicate_candidates(plan, by_condition, route=route)
            adj = await res if inspect.isawaitable(res) else res
        else:
            adj = AdjudicationResult()

        existing_adj = state.get("adjudication_result") or {}
        existing_bindings = {
            b["conditionId"]: b
            for b in existing_adj.get("conditionBindings", [])
            if "conditionId" in b
        }

        # Handle removals or reset from EditPlan
        edit_plan_dict = state.get("edit_plan") or {}
        for op in edit_plan_dict.get("operations", []):
            if op.get("action") == "REMOVE" and op.get("targetConditionId"):
                existing_bindings.pop(op["targetConditionId"], None)
            elif op.get("action") == "RESET":
                existing_bindings.clear()

        new_bindings = [
            b.model_dump(by_alias=True, exclude_none=True) if hasattr(b, "model_dump") else b
            for b in getattr(adj, "condition_bindings", [])
        ]
        for b in new_bindings:
            if "conditionId" in b:
                existing_bindings[b["conditionId"]] = b

        merged_adj = {
            "conditionBindings": list(existing_bindings.values()),
            "sortBindings": [
                s.model_dump(by_alias=True, exclude_none=True) if hasattr(s, "model_dump") else s
                for s in getattr(adj, "sort_bindings", [])
            ] or existing_adj.get("sortBindings", []),
            "clarifications": [
                c.model_dump(by_alias=True, exclude_none=True) if hasattr(c, "model_dump") else c
                for c in getattr(adj, "clarifications", [])
            ] or existing_adj.get("clarifications", []),
        }

        # Validate binding coverage
        from app.pipeline.semantic_coverage import check_binding_coverage
        try:
            adj_model = AdjudicationResult.model_validate(merged_adj)
            b_cov = check_binding_coverage(plan, adj_model)
            if not b_cov.is_valid:
                unbound_clarifications = merged_adj.get("clarifications", [])
                existing_clarified = {c.get("conditionId") for c in unbound_clarifications if isinstance(c, dict)}
                for u_id in b_cov.unbound_condition_ids:
                    if u_id not in existing_clarified:
                        unbound_clarifications.append({
                            "conditionId": u_id,
                            "code": "UNBOUND_CONDITION",
                            "message": f"条件 {u_id} 未能成功匹配有效候选",
                        })
                merged_adj["clarifications"] = unbound_clarifications
        except Exception:
            pass

        event = _make_trace(f"{route}_adjudication", t0, {"bindings": len(merged_adj["conditionBindings"])})
        return {
            "adjudication_result": merged_adj,
            "llm_calls": state.get("llm_calls", 0) + 1,
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
            "llm_calls": state.get("llm_calls", 0) + 1,
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
            "llm_calls": state.get("llm_calls", 0) + 1,
            "trace": [*state.get("trace", []), event],
        }

    async def command_build(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        if state.get("draft_dsl") is not None:
            event = _make_trace("command_build", t0, {"mode": "already_built"})
            return {"trace": [*state.get("trace", []), event]}

        resolutions = []
        if state.get("semantic_plan") and state.get("adjudication_result"):
            plan = SemanticPlan.model_validate(state["semantic_plan"])
            adj = AdjudicationResult.model_validate(state["adjudication_result"])
            contracts = {
                c["key"]: _dict_to_candidate(c)
                for c in state.get("candidate_pool", [])
            }
            catalog_map = {c.key: c for c in self._candidates_catalog}
            policy = MetricResolutionPolicy()
            as_of = state.get("as_of_date")

            def _get_metric_leaves(tree: Any) -> list[Any]:
                leaves = []
                for ch in getattr(tree, "children", []):
                    if hasattr(ch, "children") and not hasattr(ch, "mention"):
                        leaves.extend(_get_metric_leaves(ch))
                    else:
                        leaves.append(ch)
                return leaves

            binding_map = {b.condition_id: b for b in adj.condition_bindings}
            for grp in plan.groups:
                for leaf in _get_metric_leaves(grp.metric_tree):
                    if getattr(leaf, "time_hint", None) is not None:
                        res = policy.resolve(leaf.time_hint, as_of=as_of)
                        if res.metric in catalog_map:
                            contracts[res.metric] = catalog_map[res.metric]
                        cid = leaf.condition_id
                        if cid in binding_map:
                            b = binding_map[cid]
                            b.candidate_key = res.metric
                            for pk, pv in res.params.items():
                                b.params[pk] = pv
                        resolutions.append({
                            "condition_id": cid,
                            "metric": res.metric,
                            "strategy": res.strategy,
                        })

            if self._builder._default_sector_id not in contracts:
                contracts[self._builder._default_sector_id] = CandidateContract("scope", self._builder._default_sector_id, "全市场")
            dsl = self._builder.build_from_plan(plan, adj, contracts)
        else:
            contracts = [_dict_to_candidate(c) for c in state.get("candidate_pool", [])]
            keys = {c.key for c in contracts}
            if self._builder._default_sector_id not in keys:
                contracts.append(CandidateContract("scope", self._builder._default_sector_id, "全市场"))
            pool = CandidatePool(contracts)
            dsl = self._builder.build(state.get("decision", {"groups": []}), pool)

        summary = {"limit": dsl.limit, "children_count": len(dsl.children)}
        if resolutions:
            summary["metric_resolutions"] = resolutions
        event = _make_trace("command_build", t0, summary)
        return {
            "draft_dsl": dsl.model_dump(by_alias=True, exclude_none=True),
            "trace": [*state.get("trace", []), event],
        }

    async def validator(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        dsl = FundScreeningDsl.model_validate(state["draft_dsl"])
        contracts = {
            c.key: c
            for c in self._candidates_catalog
            if getattr(c, "domain", "metric") == "metric"
        }
        for c in state.get("candidate_pool", []):
            if c.get("domain") == "metric":
                contracts[c["key"]] = _dict_to_candidate(c)
        as_of_date = state.get("as_of_date")
        res = validate_and_repair(dsl, contracts, as_of_date=as_of_date)
        errors = [{"code": res.clarification.code, "message": res.clarification.message}] if res.clarification else []
        repairs = [{"code": r} for r in res.repairs]
        event = _make_trace("validator", t0, {"status": res.status, "errors": len(errors), "repairs": len(repairs)})
        updates: dict[str, Any] = {
            "draft_dsl": res.dsl.model_dump(by_alias=True, exclude_none=True),
            "validation_errors": errors,
            "repairs": [*state.get("repairs", []), *repairs],
            "repair_attempts": state.get("repair_attempts", 0) + (1 if repairs else 0),
            "trace": [*state.get("trace", []), event],
        }
        if res.status == "REJECT_OR_CLARIFY":
            if res.clarification_plan is not None:
                updates["clarification"] = res.clarification_plan.model_dump(by_alias=True)
            elif res.clarification is not None:
                updates["clarification"] = {"code": res.clarification.code, "message": res.clarification.message}
        return updates

    async def command_finalize(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        validation_errors = state.get("validation_errors", [])
        clarification = state.get("clarification")
        draft_dsl = state.get("draft_dsl")

        if validation_errors or clarification or not draft_dsl:
            event = _make_trace("command_finalize", t0, {"valid": False, "reason": "existing_errors"})
            return {
                "final_dsl": None,
                "trace": [*state.get("trace", []), event],
            }

        from app.pipeline.validator import validate_execution_gate
        contracts = {
            c["key"]: _dict_to_candidate(c)
            for c in state.get("candidate_pool", [])
            if c.get("domain") == "metric"
        }
        is_valid, gate_errors = validate_execution_gate(draft_dsl, contracts)
        if not is_valid:
            first_err = gate_errors[0]
            event = _make_trace("command_finalize", t0, {"valid": False, "reason": first_err["code"]})
            return {
                "final_dsl": None,
                "clarification": {
                    "code": first_err["code"],
                    "message": first_err["message"],
                },
                "validation_errors": gate_errors,
                "trace": [*state.get("trace", []), event],
            }

        event = _make_trace("command_finalize", t0, {"valid": True})
        return {
            "final_dsl": draft_dsl,
            "previous_dsl": draft_dsl,
            "trace": [*state.get("trace", []), event],
        }

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
        clean_q = query.strip()
        if re.fullmatch(r"\d{6}(\.[A-Za-z]+)?", clean_q):
            code_key = clean_q if "." in clean_q else f"{clean_q}.OF"
            direct_candidate = {
                "domain": "entity",
                "key": code_key,
                "name": clean_q,
                "aliases": [clean_q, code_key],
                "supported_operators": ["=", "!="],
                "value_type": "ENTITY_REF",
                "supported_usage": ["filter"],
                "binding_role": "FUND",
                "preferred_metric": "f_info_fundcode",
                "score": 1.0,
                "parameters": [],
            }
            raw_pool.insert(0, direct_candidate)

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

        semantic_plan = state.get("semantic_plan")
        adjudication = state.get("adjudication")
        if semantic_plan and adjudication and hasattr(self._builder, "build_from_plan"):
            contract_map = getattr(pool, "_contracts", {})
            dsl = self._builder.build_from_plan(semantic_plan, adjudication, contract_map)
        else:
            dsl = self._builder.build(decision, pool)

        event = _make_trace("simple_semantic_parse", t0, {"limit": dsl.limit})
        return {
            "draft_dsl": dsl.model_dump(by_alias=True, exclude_none=True),
            "trace": [*state.get("trace", []), event],
        }

    async def direct_build(self, state: FundScreeningState) -> dict[str, Any]:
        t0 = time.perf_counter()
        if state.get("draft_dsl") is not None:
            event = _make_trace("direct_build", t0, {"mode": "probed"})
            return {"trace": [*state.get("trace", []), event]}

        pool = state.get("candidate_pool") or []
        query = (state.get("normalized_query") or state.get("query", "")).strip()

        candidate = None
        for c in pool:
            if c.get("domain") == "entity" and c.get("preferred_metric") == "f_info_fundcode":
                if c.get("name") == query or query in c.get("aliases", []) or c.get("key", "").endswith(query + ".OF") or c.get("key") == query:
                    candidate = c
                    break

        if candidate is None:
            for c in pool:
                if c.get("domain") == "entity" and (c.get("score", 0.0) >= 0.95 or c.get("name") == query or query in c.get("aliases", [])):
                    candidate = c
                    break

        if candidate is None:
            if re.fullmatch(r"\d{6}(\.[A-Za-z]+)?", query):
                code_key = query if "." in query else f"{query}.OF"
                candidate = {
                    "domain": "entity",
                    "key": code_key,
                    "name": query,
                    "preferred_metric": "f_info_fundcode",
                    "value_type": "ENTITY_REF",
                }
            elif pool and pool[0].get("score", 0.0) >= 0.95:
                candidate = pool[0]
            else:
                raise ValueError("Direct routing requires a high-confidence exact candidate in candidate_pool")

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
    matched_metric_keys = set()
    condition_mentions = [item.strip() for item in re.split(r"[且与和、,，\s]+", query) if item.strip()]

    for mention in condition_mentions:
        best_candidate = None
        best_op, best_val = None, None
        for c in pool.candidates:
            if c.domain != "metric" or c.key in matched_metric_keys:
                continue
            all_names = [c.name, *c.aliases]
            if "规模" in c.name and "规模" not in all_names:
                all_names.append("规模")
            if "成立" in c.name and "成立" not in all_names:
                all_names.append("成立")
            if "回撤" in c.name and "回撤" not in all_names:
                all_names.append("回撤")
            matched_name = next((name for name in all_names if name in mention), None)
            if not matched_name:
                continue
            op, val = _extract_op_and_val(mention, matched_name, c.value_type)
            if op is not None and val is not None:
                if best_candidate is None:
                    best_candidate = c
                    best_op, best_val = op, val
                elif c.key in ("f_info_fundscale", "f_info_existingyear", "f_risk_maxdownside"):
                    best_candidate = c
                    best_op, best_val = op, val

        if best_candidate is not None:
            matched_metric_keys.add(best_candidate.key)
            unit_val = best_candidate.value_type
            if unit_val in ("NUMBER", "DECIMAL", "INTEGER", "BOOLEAN", "STRING", "DATE"):
                unit_val = "BILLION_CNY" if best_candidate.key == "f_info_fundscale" else ("PERCENT" if "return" in best_candidate.key or "downside" in best_candidate.key else None)
            metrics.append({
                "metric": best_candidate.key,
                "operator": best_op,
                "value": best_val,
                "unit": unit_val,
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


