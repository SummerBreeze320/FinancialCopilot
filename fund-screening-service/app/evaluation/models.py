from dataclasses import dataclass
from enum import StrEnum


class ErrorReason(StrEnum):
    ALIAS_MISSING = "alias_missing"
    EMBEDDING_TEXT_INSUFFICIENT = "embedding_text_insufficient"
    VECTOR_RECALL_WEAK = "vector_recall_weak"
    RERANK_ISSUE = "rerank_issue"


@dataclass(frozen=True)
class EvaluationCase:
    expected_key: str
    recalled_keys: list[str]
    error_reason: str | None = None
    query: str = ""
    expected_alias: str | None = None
    vector_keys: list[str] | None = None
    reranked_keys: list[str] | None = None
