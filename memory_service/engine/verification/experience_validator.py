from memory_service.domain.enums import VerificationStatus
from memory_service.domain.procedure import Procedure


class ExperienceValidator:
    """
    过程性经验有效性校验门禁：
    对提炼的 Procedure 进行强制结构与安全约束检查：
    1. 必须具备明确的 task_type 与 trigger_conditions
    2. 必须具备至少一条执行步骤 steps
    3. 关键业务必须包含安全/前置条件 preconditions
    4. 必须包含适用边界 applicability
    """

    def validate(self, procedure: Procedure) -> tuple[VerificationStatus, str | None]:
        if not procedure.task_type:
            return VerificationStatus.REJECTED, "Missing task_type"

        if not procedure.trigger_conditions:
            return VerificationStatus.REJECTED, "Missing trigger_conditions"

        if not procedure.steps:
            return VerificationStatus.REJECTED, "Procedure execution steps cannot be empty"

        if not procedure.applicability:
            return VerificationStatus.REJECTED, "Missing applicability bounds"

        # 检查步骤格式
        for i, step in enumerate(procedure.steps):
            if isinstance(step, dict):
                if "action" not in step and "description" not in step:
                    return VerificationStatus.REJECTED, f"Step {i+1} missing action or description"
            elif not isinstance(step, str):
                return VerificationStatus.REJECTED, f"Invalid step format at index {i}"

        return VerificationStatus.VALIDATED, None
