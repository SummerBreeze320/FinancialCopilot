from app.models.dsl import FundScreeningDsl
from app.pipeline.preprocess import preprocess
from app.pipeline.mvp import compile_mvp_query
from app.pipeline.editor import EditKind, apply_simple_edit, classify_edit
from app.pipeline.validator import validate_and_repair

STAGES = ("query_preprocess", "query_analysis_and_recall", "command_build", "validator_and_repair", "command_finalize")


class ScreeningOrchestrator:
    async def run(self, query: str, previous_dsl: dict | None = None, emit=None) -> FundScreeningDsl:
        async def stage(name: str) -> None:
            if emit:
                await emit({"type": "stage_started", "stage": name})
                await emit({"type": "stage_completed", "stage": name})
        await stage(STAGES[0]); prepared = preprocess(query, previous_dsl)
        await stage(STAGES[1]); await stage(STAGES[2])
        edit_kind = classify_edit(prepared.normalized_query, previous_dsl)
        if edit_kind is EditKind.SIMPLE and previous_dsl is not None:
            dsl = apply_simple_edit(FundScreeningDsl.model_validate(previous_dsl), prepared.normalized_query)
        else:
            dsl = compile_mvp_query(prepared.normalized_query)
        await stage(STAGES[3]); dsl = validate_and_repair(dsl).dsl
        await stage(STAGES[4])
        return dsl
