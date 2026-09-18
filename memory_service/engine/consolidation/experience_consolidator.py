from typing import Any
from memory_service.domain.enums import ConsolidationAction
from memory_service.domain.procedure import Procedure


class ProcedureConsolidationResult:
    def __init__(
        self,
        action: ConsolidationAction,
        consolidated_procedure: Procedure,
        target_memory_id: str | None = None,
        reason: str = "",
    ):
        self.action = action
        self.consolidated_procedure = consolidated_procedure
        self.target_memory_id = target_memory_id
        self.reason = reason


class ExperienceConsolidator:
    """
    过程性经验整合与演化器：
    对比新旧 Procedure：
    - 若已有同类 task_type 的 Procedure：
      - 合并新发现的 failure_signatures 与 recovery_actions
      - 丰富/补充前置检查 preconditions
      - 若产生实质性增强，则返回 REFINE，触发该经验升级下一代版本 (v+1)
      - 若内容无新增收益，返回 CORROBORATE
    - 若无历史同类经验，返回 CREATE
    """

    def consolidate(
        self,
        new_proc: Procedure,
        existing_proc: Procedure | None,
        existing_memory_id: str | None = None,
    ) -> ProcedureConsolidationResult:
        if not existing_proc:
            return ProcedureConsolidationResult(
                action=ConsolidationAction.CREATE,
                consolidated_procedure=new_proc,
                reason="No existing procedure found for task_type, create as v1",
            )

        existing_fails = set(existing_proc.failure_signatures)
        new_fails = set(new_proc.failure_signatures)
        added_fails = new_fails - existing_fails

        existing_preconditions = set(existing_proc.preconditions)
        new_preconditions = set(new_proc.preconditions)
        added_preconditions = new_preconditions - existing_preconditions

        existing_recovery = set(existing_proc.recovery_actions)
        new_recovery = set(new_proc.recovery_actions)
        added_recovery = new_recovery - existing_recovery

        if not added_fails and not added_preconditions and not added_recovery and len(existing_proc.steps) == len(new_proc.steps):
            return ProcedureConsolidationResult(
                action=ConsolidationAction.CORROBORATE,
                consolidated_procedure=existing_proc,
                target_memory_id=existing_memory_id,
                reason="Existing procedure already covers all features, corroborated",
            )

        merged_preconditions = sorted(list(existing_preconditions.union(new_preconditions)))
        merged_failures = sorted(list(existing_fails.union(new_fails)))
        merged_recovery = sorted(list(existing_recovery.union(new_recovery)))

        consolidated = Procedure(
            task_type=existing_proc.task_type,
            trigger_conditions=existing_proc.trigger_conditions or new_proc.trigger_conditions,
            preconditions=merged_preconditions,
            steps=new_proc.steps or existing_proc.steps,
            applicability={**existing_proc.applicability, **new_proc.applicability},
            validation_rules=list(set(existing_proc.validation_rules + new_proc.validation_rules)),
            failure_signatures=merged_failures,
            recovery_actions=merged_recovery,
        )

        return ProcedureConsolidationResult(
            action=ConsolidationAction.REFINE,
            consolidated_procedure=consolidated,
            target_memory_id=existing_memory_id,
            reason=f"Procedure refined with new preconditions ({len(added_preconditions)}), failures ({len(added_fails)}), and recoveries ({len(added_recovery)})",
        )
