from __future__ import annotations

import re
from typing import Any, Literal
from pydantic import BaseModel, ConfigDict, Field


class ExpectedCondition(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    mention: str
    domain: Literal["metric", "scope", "entity"]
    relevant_candidate_keys: list[str] = Field(alias="relevantCandidateKeys")
    primary_candidate_key: str | None = Field(default=None, alias="primaryCandidateKey")


class ExpectedTarget(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    metric: str | None = None
    sector: str | None = None
    operator: str | None = None
    value: Any | None = None
    unit: str | None = None
    params: dict[str, Any] = Field(default_factory=dict)


class GoldenCase(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    category: Literal["metric", "sector", "entity", "scenario", "regression"]
    query: str
    difficulty: Literal["clear", "ambiguous", "complex"] = "clear"
    as_of_date: str | None = Field(default=None, alias="asOfDate")
    expected: ExpectedTarget | None = None
    expected_outcome: Literal["DSL", "CLARIFICATION"] = Field(default="DSL", alias="expectedOutcome")
    expected_conditions: list[ExpectedCondition] = Field(default_factory=list, alias="expectedConditions")
    expected_dsl: dict[str, Any] | None = Field(default=None, alias="expectedDsl")
    expected_clarification: dict[str, Any] | None = Field(default=None, alias="expectedClarification")


_SENSITIVE_KEYS = {
    "userId", "user_id", "accountId", "account_id",
    "conversationId", "conversation_id", "sessionId", "session_id",
}

_PII_PATTERNS = [
    (r"(?<!\d)1[3-9]\d{9}(?!\d)", "[PHONE]"),
    (r"(?<!\d)\d{16,19}(?!\d)", "[ACCOUNT]"),
    (r"(?:身份证|ID)[\s:：]*\d{17}[\dXx]", "[ID_CARD]"),
]


def validate_and_sanitize_regression_case(case_dict: dict[str, Any]) -> dict[str, Any]:
    sanitized = {k: v for k, v in case_dict.items() if k not in _SENSITIVE_KEYS}
    query = sanitized.get("query", "")
    for pattern, repl in _PII_PATTERNS:
        query = re.sub(pattern, repl, query)
    sanitized["query"] = query
    return sanitized
