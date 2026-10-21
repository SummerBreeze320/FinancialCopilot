from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


LogicOperator = Literal["AND", "OR", "NOT"]


class SortItem(BaseModel):
    metric: str
    direction: Literal["ASC", "DESC"]
    nulls: Literal["FIRST", "LAST"] = "LAST"


from pydantic import BaseModel, ConfigDict, Field, model_validator, TypeAdapter
from app.models.dsl_nodes import MetricPredicate, MetricLogicNode, MetricNode

MetricCondition = MetricPredicate
MetricNodeAdapter = TypeAdapter(MetricNode)
MetricExpression = MetricNode



class SectorLeaf(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    sector_id: str = Field(alias="sectorId")


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
    metric: MetricNode
    sort: list[SortItem] = Field(default_factory=list)
    limit: int = Field(default=20, gt=0)




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


def metric_leaves(node_or_dsl: Any) -> list[MetricPredicate]:
    leaves: list[MetricPredicate] = []
    if isinstance(node_or_dsl, FundScreeningDsl):
        for grp in node_or_dsl.children:
            leaves.extend(metric_leaves(grp.metric))
    elif isinstance(node_or_dsl, BusinessGroup):
        leaves.extend(metric_leaves(node_or_dsl.metric))
    elif isinstance(node_or_dsl, MetricPredicate):
        leaves.append(node_or_dsl)
    elif isinstance(node_or_dsl, MetricLogicNode):
        for child in node_or_dsl.children:
            leaves.extend(metric_leaves(child))
    return leaves
