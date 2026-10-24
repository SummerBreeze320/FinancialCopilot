from __future__ import annotations

from typing import Any, Literal
from pydantic import BaseModel, ConfigDict, Field

from app.models.semantic_plan import MetricLeaf, ScopeLeaf, SortMention

EditAction = Literal["ADD", "REPLACE", "REMOVE", "CHANGE_OPERATOR", "RESET"]
EditTargetScope = Literal["ROOT", "GROUP", "ALL_GROUPS"]


class EditOperation(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    action: EditAction
    target_condition_id: str | None = Field(default=None, alias="targetConditionId")
    group_id: str | None = Field(default=None, alias="groupId")
    target_scope: EditTargetScope | None = Field(default=None, alias="targetScope")
    condition: MetricLeaf | ScopeLeaf | None = None
    operator: Literal["AND", "OR", "NOT"] | None = None


class EditPlan(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    intent: Literal["EDIT"] = "EDIT"
    operations: list[EditOperation] = Field(default_factory=list)
    sort_mentions: list[SortMention] | None = Field(default=None, alias="sortMentions")
    limit: int | None = None
    target_scope: EditTargetScope | None = Field(default=None, alias="targetScope")
