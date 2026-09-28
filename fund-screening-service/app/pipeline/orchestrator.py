from app.models.dsl import FundScreeningDsl
from app.pipeline.preprocess import preprocess
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
        dsl = FundScreeningDsl.model_validate({"version":"fund-screening/v1","operator":"AND","children":[{"sector":{"operator":"OR","children":[{"sectorId":"1000019220000000"}]},"metric":{"operator":"AND","children":[{"metric":"f_info_existingyear","operator":">","value":10,"unit":"YEAR"}]}}],"limit":20})
        await stage(STAGES[3]); dsl = validate_and_repair(dsl).dsl
        await stage(STAGES[4])
        return dsl
