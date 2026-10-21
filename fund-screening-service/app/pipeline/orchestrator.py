from __future__ import annotations

from typing import Any
from app.models.dsl import FundScreeningDsl
from app.pipeline.preprocess import preprocess
from app.pipeline.mvp import compile_mvp_query
from app.pipeline.editor import EditKind, apply_simple_edit, classify_edit
from app.pipeline.validator import validate_and_repair

STAGES = ("query_preprocess", "query_analysis_and_recall", "command_build", "validator_and_repair", "command_finalize")

NODE_TO_STAGE = {
    "query_preprocess": "query_preprocess",
    "strict_direct_probe": "query_preprocess",
    "semantic_plan": "query_analysis_and_recall",
    "recall_by_condition": "query_analysis_and_recall",
    "simple_full_router": "query_analysis_and_recall",
    "simple_adjudication": "query_analysis_and_recall",
    "full_adjudication": "query_analysis_and_recall",
    "pre_route_recall": "query_analysis_and_recall",
    "route_classifier": "query_analysis_and_recall",
    "pre_recall_plan": "query_analysis_and_recall",
    "parallel_recall": "query_analysis_and_recall",
    "candidate_adjudication": "query_analysis_and_recall",
    "simple_edit": "command_build",
    "direct_build": "command_build",
    "simple_semantic_parse": "command_build",
    "command_build": "command_build",
    "validator": "validator_and_repair",
    "mechanical_repair": "validator_and_repair",
    "command_finalize": "command_finalize",
}


class ClarificationRequiredError(Exception):
    def __init__(self, code: str, message: str, clarification: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.clarification = clarification or {"code": code, "message": message}


class ScreeningOrchestrator:
    def __init__(self, compiler: Any = None, graph: Any = None) -> None:
        self._compiler = compiler
        self._graph = graph

    async def run_with_trace(
        self,
        query: str,
        previous_dsl: dict[str, Any] | None = None,
        conversation_id: str | None = None,
    ) -> dict[str, Any]:
        if self._graph is not None:
            from app.graph.checkpoint import make_graph_config
            from app.graph.state import empty_state

            state = empty_state(query, conversation_id=conversation_id, previous_dsl=previous_dsl)
            config = make_graph_config(conversation_id)
            final_state = state
            async for update in self._graph.astream(state, config=config, stream_mode="updates"):
                for _, node_update in update.items():
                    final_state = {**final_state, **node_update}

            raw_dsl = final_state.get("final_dsl") or final_state.get("draft_dsl")
            clarification = final_state.get("clarification")
            dsl_dump = (
                FundScreeningDsl.model_validate(raw_dsl).model_dump(by_alias=True, exclude_none=True)
                if raw_dsl
                else None
            )
            return {
                "dsl": dsl_dump,
                "clarification": clarification,
                "route": final_state.get("route", "full"),
                "trace": final_state.get("trace", []),
                "repairs": final_state.get("repairs", []),
                "candidateCount": len(final_state.get("candidate_pool", [])),
            }
        dsl = await self.run(query, previous_dsl=previous_dsl, conversation_id=conversation_id)
        return {"dsl": dsl.model_dump(by_alias=True, exclude_none=True), "clarification": None, "route": "simple"}

    async def run(
        self,
        query: str,
        previous_dsl: dict[str, Any] | None = None,
        emit: Any = None,
        conversation_id: str | None = None,
    ) -> FundScreeningDsl:
        # 1. 优先使用已编译 LangGraph 执行
        if self._graph is not None:
            from app.graph.checkpoint import make_graph_config
            from app.graph.state import empty_state

            state = empty_state(query, conversation_id=conversation_id, previous_dsl=previous_dsl)
            config = make_graph_config(conversation_id)

            final_state = state
            stage_order = {s: i for i, s in enumerate(STAGES)}
            emitted_stages = set()
            async for update in self._graph.astream(state, config=config, stream_mode="updates"):
                for node_name, node_update in update.items():
                    final_state = {**final_state, **node_update}
                    if emit:
                        trace_list = node_update.get("trace", [])
                        summary = trace_list[-1]["summary"] if trace_list else {}

                        mapped_stage = NODE_TO_STAGE.get(node_name)
                        if mapped_stage:
                            target_idx = stage_order.get(mapped_stage, 0)
                            for prev_stage in STAGES[:target_idx]:
                                if prev_stage not in emitted_stages:
                                    emitted_stages.add(prev_stage)
                                    await emit({"type": "stage_started", "stage": prev_stage})
                                    await emit({
                                        "type": "stage_completed",
                                        "stage": prev_stage,
                                        "status": "completed",
                                        "summary": {"bypassed": True},
                                    })

                            if mapped_stage not in emitted_stages:
                                emitted_stages.add(mapped_stage)
                                await emit({"type": "stage_started", "stage": mapped_stage})
                                await emit({
                                    "type": "stage_completed",
                                    "stage": mapped_stage,
                                    "status": "completed",
                                    "summary": summary,
                                })

            if emit:
                for rem_stage in STAGES:
                    if rem_stage not in emitted_stages:
                        emitted_stages.add(rem_stage)
                        await emit({"type": "stage_started", "stage": rem_stage})
                        await emit({
                            "type": "stage_completed",
                            "stage": rem_stage,
                            "status": "completed",
                            "summary": {"bypassed": True},
                        })

            raw_dsl = final_state.get("final_dsl") or final_state.get("draft_dsl")
            if raw_dsl:
                return FundScreeningDsl.model_validate(raw_dsl)
            if final_state.get("clarification"):
                clarification = final_state["clarification"]
                code = clarification.get("code", "CLARIFICATION_REQUIRED") if isinstance(clarification, dict) else "CLARIFICATION_REQUIRED"
                msg = clarification.get("message", str(clarification)) if isinstance(clarification, dict) else str(clarification)
                clar_dict = clarification if isinstance(clarification, dict) else {"code": code, "message": msg}
                raise ClarificationRequiredError(code, msg, clar_dict)
            raise RuntimeError("Screening compilation failed to produce a valid DSL")


        # 2. 兼容外部 compiler 或 MVP 流程
        async def stage(name: str) -> None:
            if emit:
                await emit({"type": "stage_started", "stage": name})
                await emit({"type": "stage_completed", "stage": name, "status": "completed", "summary": {}})

        await stage(STAGES[0]); prepared = preprocess(query, previous_dsl)
        await stage(STAGES[1]); await stage(STAGES[2])
        edit_kind = classify_edit(prepared.normalized_query, previous_dsl)
        if edit_kind is EditKind.SIMPLE and previous_dsl is not None:
            dsl = apply_simple_edit(FundScreeningDsl.model_validate(previous_dsl), prepared.normalized_query)
        elif self._compiler is not None:
            dsl = (await self._compiler.compile(prepared.normalized_query)).dsl
        else:
            dsl = compile_mvp_query(prepared.normalized_query)
        await stage(STAGES[3]); dsl = validate_and_repair(dsl).dsl
        await stage(STAGES[4])
        return dsl
