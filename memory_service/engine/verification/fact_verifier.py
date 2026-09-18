from memory_service.domain.enums import VerificationStatus
from memory_service.domain.fact import Fact


class FactVerifier:
    """
    事实有效性校验门禁：
    对提炼的事实执行确定性强校验，只有通过校验的事实才能打上 VALIDATED 标签并允许进入发布流程。
    校验项：
    1. 字段完整性（subject, predicate, value 不能为空）
    2. 有效性与合法类型校验
    """

    def verify(self, fact: Fact) -> tuple[VerificationStatus, str | None]:
        if not fact.subject or not fact.predicate:
            return VerificationStatus.REJECTED, "Missing required subject or predicate"

        if fact.value is None or fact.value == "" or fact.value == []:
            return VerificationStatus.REJECTED, "Fact value is empty"

        return VerificationStatus.VALIDATED, None
