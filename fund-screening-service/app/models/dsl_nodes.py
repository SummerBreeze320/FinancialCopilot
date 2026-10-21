from __future__ import annotations

from typing import Any, Literal, Union
from pydantic import BaseModel, Field, model_validator


class MetricPredicate(BaseModel):
    metric: str
    operator: Literal[">", ">=", "<", "<=", "=", "!="]
    value: Any
    unit: str | None = None
    period: dict[str, Any] | None = None
    params: list[dict[str, Any]] = Field(default_factory=list)


class MetricLogicNode(BaseModel):
    operator: Literal["AND", "OR", "NOT"]
    children: list[MetricNode]

    @model_validator(mode="after")
    def validate_children(self) -> MetricLogicNode:
        if not self.children:
            raise ValueError("MetricLogicNode requires children")
        if self.operator == "NOT" and len(self.children) != 1:
            raise ValueError("NOT requires exactly one child")
        return self


MetricNode = Union[MetricPredicate, MetricLogicNode]
MetricLogicNode.model_rebuild()
