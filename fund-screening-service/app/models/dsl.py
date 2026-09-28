from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


LogicOperator = Literal["AND", "OR", "NOT"]


class SortItem(BaseModel):
    metric: str
    direction: Literal["ASC", "DESC"]
    nulls: Literal["FIRST", "LAST"] = "LAST"


class MetricPredicate(BaseModel):
    metric: str
    operator: Literal[">", ">=", "<", "<=", "=", "!="]
    value: Any
    unit: str
    period: dict[str, Any] | None = None


class MetricExpression(BaseModel):
    """递归指标表达式；叶子含 metric，非叶子含 children。"""

    operator: str
    metric: str | None = None
    value: Any | None = None
    unit: str | None = None
    period: dict[str, Any] | None = None
    children: list[MetricExpression] | None = None

    @model_validator(mode="after")
    def validate_shape(self) -> MetricExpression:
        if self.metric is not None:
            if self.operator not in {">", ">=", "<", "<=", "=", "!="} or self.unit is None:
                raise ValueError("metric predicate requires a comparison operator and unit")
            if self.children is not None:
                raise ValueError("metric predicate cannot have children")
            return self
        if self.operator not in {"AND", "OR", "NOT"} or not self.children:
            raise ValueError("metric group requires a logical operator and children")
        if self.operator == "NOT" and len(self.children) != 1:
            raise ValueError("NOT requires exactly one child")
        return self


class SectorLeaf(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    sector_id: str = Field(alias="sectorId")
    match: Literal["SELF", "SELF_OR_DESCENDANT"] = "SELF_OR_DESCENDANT"


class SectorExpression(BaseModel):
    operator: LogicOperator
    children: list[SectorExpression | SectorLeaf]

    @model_validator(mode="after")
    def validate_children(self) -> SectorExpression:
        if not self.children:
            raise ValueError("sector expression requires children")
        if self.operator == "NOT" and len(self.children) != 1:
            raise ValueError("NOT requires exactly one child")
        return self


class BusinessGroup(BaseModel):
    sector: SectorExpression
    metric: MetricExpression
    sort: list[SortItem] = Field(default_factory=list)
    limit: int | None = Field(default=None, gt=0)


class FundScreeningDsl(BaseModel):
    version: Literal["fund-screening/v1"]
    operator: LogicOperator
    children: list[BusinessGroup]
    sort: list[SortItem] = Field(default_factory=list)
    limit: int = Field(gt=0)

    @model_validator(mode="after")
    def validate_root(self) -> FundScreeningDsl:
        if not self.children:
            raise ValueError("root requires business groups")
        if self.operator == "NOT" and len(self.children) != 1:
            raise ValueError("NOT requires exactly one child")
        return self
