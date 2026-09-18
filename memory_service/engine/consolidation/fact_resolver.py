from typing import Any
from memory_service.domain.enums import ConsolidationAction
from memory_service.domain.fact import Fact, FactSlot


class FactResolutionResult:
    def __init__(
        self,
        action: ConsolidationAction,
        resolved_value: Any,
        target_memory_id: str | None = None,
        reason: str = "",
    ):
        self.action = action
        self.resolved_value = resolved_value
        self.target_memory_id = target_memory_id
        self.reason = reason


class FactResolver:
    """
    事实消解与冲突演化器：
    对比新提炼的事实与现有事实槽位 (FactSlot)，依据断言类型判定版本演进动作：
    - CORROBORATE: 值完全相同，已有证据得到再印证，无需新建版本
    - CORRECT: 单值事实被新证据修正，触发新版本与指针切换
    - REFINE: 集合型/多值事实进行增量合并精化
    - CREATE: 该槽位尚无历史记录，首次创建
    """

    def resolve(
        self,
        new_fact: Fact,
        existing_slot: FactSlot | None,
    ) -> FactResolutionResult:
        if not existing_slot:
            return FactResolutionResult(
                action=ConsolidationAction.CREATE,
                resolved_value=new_fact.value,
                reason="No existing slot found, creating new fact",
            )

        old_val = existing_slot.value

        # 如果值是列表/集合（例如排除行业、偏好标的等）
        if isinstance(new_fact.value, (list, set)) or isinstance(old_val, (list, set)):
            old_set = set(old_val) if isinstance(old_val, (list, set)) else {old_val}
            new_set = set(new_fact.value) if isinstance(new_fact.value, (list, set)) else {new_fact.value}
            merged_set = old_set.union(new_set)

            if merged_set == old_set:
                return FactResolutionResult(
                    action=ConsolidationAction.CORROBORATE,
                    resolved_value=sorted(list(old_set)),
                    target_memory_id=existing_slot.memory_id,
                    reason="Set already contains all elements",
                )
            else:
                return FactResolutionResult(
                    action=ConsolidationAction.REFINE,
                    resolved_value=sorted(list(merged_set)),
                    target_memory_id=existing_slot.memory_id,
                    reason=f"Refined set with new elements: {sorted(list(new_set - old_set))}",
                )

        # 单值对比
        if old_val == new_fact.value:
            return FactResolutionResult(
                action=ConsolidationAction.CORROBORATE,
                resolved_value=old_val,
                target_memory_id=existing_slot.memory_id,
                reason="Identical single value, corroborated",
            )
        else:
            return FactResolutionResult(
                action=ConsolidationAction.CORRECT,
                resolved_value=new_fact.value,
                target_memory_id=existing_slot.memory_id,
                reason=f"Single value corrected from {old_val} to {new_fact.value}",
            )
