from __future__ import annotations

from typing import Any
from pydantic import BaseModel, ConfigDict, Field

from app.models.semantic_plan import ResolvedCondition, SemanticPlan


class ClarificationPlan(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    code: str
    message: str
    ambiguous_mentions: list[str] = Field(default_factory=list, alias="ambiguousMentions")
    candidate_interpretations: dict[str, list[str]] = Field(default_factory=dict, alias="candidateInterpretations")
    question: str = ""
    blocked_condition_ids: list[str] = Field(default_factory=list, alias="blockedConditionIds")
    resolved_conditions: list[ResolvedCondition] = Field(default_factory=list, alias="resolvedConditions")
    preserved_plan: SemanticPlan | None = Field(default=None, alias="preservedPlan")
