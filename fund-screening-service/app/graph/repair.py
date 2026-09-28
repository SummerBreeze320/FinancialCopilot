from __future__ import annotations

import time
from typing import Any, Literal

from app.graph.nodes import _make_trace
from app.graph.state import ClarificationPayload, FundScreeningState
from app.models.candidate import CandidateContract
from app.models.dsl import FundScreeningDsl
from app.pipeline.validator import validate_and_repair

REPAIRABLE_CODES = {
    "NEGATIVE_DRAWDOWN_STORAGE",
    "DEFAULT_GROUP_SORT",
    "DEFAULT_SECTOR_REQUIRED",
    "DEFAULT_METRIC_PARAMETER",
}


def decide_validation_outcome(state: FundScreeningState) -> Literal["finalize", "repair", "clarify"]:
    errors = state.get("validation_errors", [])
    if not errors:
        return "finalize"

    attempts = state.get("repair_attempts", 0)
    if attempts >= 2:
        return "clarify"

    if all(e.get("code") in REPAIRABLE_CODES for e in errors):
        return "repair"

    return "clarify"


async def mechanical_repair_node(state: FundScreeningState) -> dict[str, Any]:
    t0 = time.perf_counter()
    dsl = FundScreeningDsl.model_validate(state["draft_dsl"])
    contracts = {
        c["key"]: CandidateContract(
            domain=c["domain"],
            key=c["key"],
            name=c["name"],
            supported_usage=tuple(c.get("supported_usage", ("filter",))),
        )
        for c in state.get("candidate_pool", [])
        if c.get("domain") == "metric"
    }

    repaired_res = validate_and_repair(dsl, contracts)
    attempts = state.get("repair_attempts", 0) + 1
    new_repairs = [{"code": r} for r in repaired_res.repairs]
    remaining_errors = (
        [{"code": repaired_res.clarification.code, "message": repaired_res.clarification.message}]
        if repaired_res.clarification
        else []
    )

    event = _make_trace("mechanical_repair", t0, {"attempt": attempts, "repairs": [r["code"] for r in new_repairs]})
    return {
        "draft_dsl": repaired_res.dsl.model_dump(by_alias=True, exclude_none=True),
        "validation_errors": remaining_errors,
        "repairs": [*state.get("repairs", []), *new_repairs],
        "repair_attempts": attempts,
        "trace": [*state.get("trace", []), event],
    }


async def clarification_node(state: FundScreeningState) -> dict[str, Any]:
    t0 = time.perf_counter()
    errors = state.get("validation_errors", [])
    code = errors[0]["code"] if errors else "VALIDATION_REPAIR_EXHAUSTED"
    message = errors[0]["message"] if errors else "筛选条件校验修复次数超限，请明确具体指标或参数。"
    clarification: ClarificationPayload = {
        "code": code,
        "message": message,
        "suggestions": ["调整指标阈值", "检查排序指标是否支持", "补充必要参数"],
    }
    event = _make_trace("clarification", t0, {"clarification_code": code})
    return {
        "clarification": clarification,
        "final_dsl": None,
        "trace": [*state.get("trace", []), event],
    }
