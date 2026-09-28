from dataclasses import dataclass

from app.models.dsl import FundScreeningDsl
from app.pipeline.builder import CommandBuilder
from app.pipeline.validator import ValidationResult, validate_and_repair


@dataclass(frozen=True)
class CompileResult:
    dsl: FundScreeningDsl
    trace: dict
    validation: ValidationResult


class RagCommandCompiler:
    """串联 plan、三路召回、候选裁决、DSL 构建及校验修复。"""

    def __init__(self, analysis_service, builder: CommandBuilder | None = None) -> None:
        self._analysis = analysis_service
        self._builder = builder or CommandBuilder()

    async def compile(self, query: str) -> CompileResult:
        analysis = await self._analysis.analyze_recall_and_decide(query)
        dsl = self._builder.build(analysis.decision, analysis.candidate_pool)
        contracts = {
            item.key: item for item in analysis.candidate_pool.candidates if item.domain == "metric"
        }
        validation = validate_and_repair(dsl, contracts)
        return CompileResult(
            validation.dsl,
            {
                "route": "full",
                "condition_count": len(analysis.plan.conditions),
                "candidate_count": len(analysis.candidate_pool.candidates),
                "repairs": list(validation.repairs),
            },
            validation,
        )
