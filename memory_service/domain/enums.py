from enum import StrEnum


class MemoryType(StrEnum):
    FACT = "FACT"
    PREFERENCE = "PREFERENCE"
    PROCEDURE = "PROCEDURE"


class MemoryStatus(StrEnum):
    DRAFT = "DRAFT"
    ACTIVE = "ACTIVE"
    SUPERSEDED = "SUPERSEDED"
    REJECTED = "REJECTED"
    DELETED = "DELETED"


class VerificationStatus(StrEnum):
    UNVERIFIED = "UNVERIFIED"
    VALIDATED = "VALIDATED"
    REJECTED = "REJECTED"


class ConsolidationAction(StrEnum):
    CREATE = "CREATE"
    CORROBORATE = "CORROBORATE"
    REFINE = "REFINE"
    CORRECT = "CORRECT"


class AssertionType(StrEnum):
    EXPLICIT = "EXPLICIT"    # 用户明确陈述
    INFERRED = "INFERRED"    # 模型推断（需严格验证门禁）


class EvidenceType(StrEnum):
    USER_STATEMENT = "USER_STATEMENT"
    EXECUTION_TRACE = "EXECUTION_TRACE"
    VALIDATION_RULE = "VALIDATION_RULE"
    ASSISTANT_CONFIRM = "ASSISTANT_CONFIRM"


class JobStatus(StrEnum):
    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
