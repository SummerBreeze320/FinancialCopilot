from __future__ import annotations

from typing import Any
from app.graph.checkpoint import make_graph_config
from app.graph.state import empty_state
from app.models.dsl import FundScreeningDsl
from app.models.semantic_plan import MetricLeaf, ScopeLeaf, SemanticPlan
from app.pipeline.compiler import CompileResult
from app.pipeline.validator import ValidationResult


class LangGraphCommandCompiler:
    """基于编译后的 LangGraph 工作流驱动的基金筛选编译器。"""

    def __init__(self, graph: Any) -> None:
        self.graph = graph

    async def compile(
        self,
        query: str,
        previous_dsl: dict | None = None,
        conversation_id: str | None = None,
    ) -> CompileResult:
        state = empty_state(query, conversation_id=conversation_id, previous_dsl=previous_dsl)
        config = make_graph_config(conversation_id)
        result = await self.graph.ainvoke(state, config=config)

        raw_dsl = result.get("final_dsl") or result.get("draft_dsl")
        if raw_dsl is None:
            raise RuntimeError(f"Screening compilation failed: {result.get('clarification')}")
        final_dsl = FundScreeningDsl.model_validate(raw_dsl)

        if result.get("semantic_plan"):
            plan_raw = result["semantic_plan"]
            plan = SemanticPlan.model_validate(plan_raw) if isinstance(plan_raw, dict) else plan_raw
            by_condition = result.get("by_condition") or {}
            adj_dict = result.get("adjudication_result") or {}
            bindings = adj_dict.get("conditionBindings") or adj_dict.get("condition_bindings") or []

            def _count_conditions(p: SemanticPlan) -> int:
                cnt = 0

                def _count_scope(tree: Any):
                    nonlocal cnt
                    for ch in getattr(tree, "children", []):
                        if isinstance(ch, ScopeLeaf) or (hasattr(ch, "mention") and not hasattr(ch, "children")):
                            cnt += 1
                        elif hasattr(ch, "children"):
                            _count_scope(ch)

                def _count_metric(tree: Any):
                    nonlocal cnt
                    for ch in getattr(tree, "children", []):
                        if isinstance(ch, MetricLeaf) or (hasattr(ch, "mention") and not hasattr(ch, "children")):
                            cnt += 1
                        elif hasattr(ch, "children"):
                            _count_metric(ch)

                for g in p.groups:
                    _count_scope(g.scope_tree)
                    _count_metric(g.metric_tree)
                return cnt

            group_count = len(plan.groups)
            condition_count = _count_conditions(plan)
            recalled_condition_count = len(by_condition)
            candidate_count = len(result.get("candidate_pool", []))
            binding_count = len(bindings)
        else:
            plan_legacy = result.get("recall_plan") or {}
            group_count = len(final_dsl.children) if final_dsl else 0
            condition_count = len(plan_legacy.get("conditions", []))
            recalled_condition_count = len(result.get("by_condition") or {})
            candidate_count = len(result.get("candidate_pool", []))
            binding_count = 0

        trace = {
            "route": result.get("route", "full"),
            "route_reason": result.get("route_reason"),
            "group_count": group_count,
            "condition_count": condition_count,
            "recalled_condition_count": recalled_condition_count,
            "candidate_count": candidate_count,
            "binding_count": binding_count,
            "llm_calls": result.get("llm_calls", 0),
            "repairs": [r.get("code") for r in result.get("repairs", [])],
        }
        return CompileResult(final_dsl, trace, ValidationResult(final_dsl))
