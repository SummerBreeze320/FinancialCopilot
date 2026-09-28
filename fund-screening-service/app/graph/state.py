from __future__ import annotations

import uuid
from typing import Any, Literal, TypedDict


class TraceEvent(TypedDict):
    stage: str
    duration_ms: float
    summary: dict[str, Any]
    status: Literal["completed", "failed", "skipped"]


class ClarificationPayload(TypedDict):
    code: str
    message: str
    suggestions: list[str]


class FundScreeningState(TypedDict, total=False):
    request_id: str
    conversation_id: str | None
    query: str
    normalized_query: str
    previous_dsl: dict[str, Any] | None
    intent: Literal["CREATE", "EDIT"]
    edit_complexity: Literal["NONE", "SIMPLE", "COMPLEX"]
    route: Literal["direct", "simple", "full"]

    recall_plan: dict[str, Any] | None
    candidate_pool: list[dict[str, Any]]
    candidate_contract_index: dict[str, dict[str, Any]]
    decision: dict[str, Any] | None

    draft_dsl: dict[str, Any] | None
    final_dsl: dict[str, Any] | None
    validation_errors: list[dict[str, Any]]
    repairs: list[dict[str, Any]]
    clarification: ClarificationPayload | None
    repair_attempts: int
    trace: list[TraceEvent]


def empty_state(
    query: str,
    conversation_id: str | None = None,
    previous_dsl: dict[str, Any] | None = None,
) -> FundScreeningState:
    state: FundScreeningState = {
        "request_id": str(uuid.uuid4()),
        "conversation_id": conversation_id,
        "query": query,
        "normalized_query": query,
        "intent": "EDIT" if previous_dsl else "CREATE",
        "edit_complexity": "NONE",
        "route": "full",
        "candidate_pool": [],
        "candidate_contract_index": {},
        "draft_dsl": None,
        "final_dsl": None,
        "validation_errors": [],
        "repairs": [],
        "clarification": None,
        "repair_attempts": 0,
        "trace": [],
    }
    if previous_dsl is not None:
        state["previous_dsl"] = previous_dsl
    return state

