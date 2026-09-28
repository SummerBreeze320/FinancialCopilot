from __future__ import annotations

from typing import Any
from app.graph.checkpoint import make_graph_config
from app.graph.state import empty_state
from app.models.dsl import FundScreeningDsl
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

        plan = result.get("recall_plan") or {}
        trace = {
            "route": result.get("route", "full"),
            "condition_count": len(plan.get("conditions", [])),
            "candidate_count": len(result.get("candidate_pool", [])),
            "repairs": [r.get("code") for r in result.get("repairs", [])],
        }
        return CompileResult(final_dsl, trace, ValidationResult(final_dsl))
