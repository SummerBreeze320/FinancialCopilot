from __future__ import annotations

from typing import Any, Literal
from pydantic import BaseModel, ConfigDict, Field


class ConditionOutput(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    condition_id: str = Field(alias="conditionId", description="条件唯一标识，如 c1, c2")
    mention: str = Field(description="用户输入的原始提及词")
    domain: Literal["metric", "scope", "entity"] = Field(description="所属领域")


class RecallPlanOutput(BaseModel):
    domains: list[Literal["metric", "scope", "entity"]] = Field(default_factory=list)
    conditions: list[ConditionOutput] = Field(default_factory=list)


class MetricPredicateOutput(BaseModel):
    metric: str
    operator: Literal[">", ">=", "<", "<=", "=", "!="]
    value: Any
    unit: str
    params: list[dict[str, Any]] = Field(default_factory=list)
    period: dict[str, Any] | None = None


class EntityRefOutput(BaseModel):
    entity: str
    operator: Literal["=", "!="] = "="


class SortItemOutput(BaseModel):
    metric: str
    direction: Literal["ASC", "DESC"] = "DESC"
    nulls: Literal["FIRST", "LAST"] = "LAST"


class DecisionGroupOutput(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    operator: Literal["AND", "OR", "NOT"] = "AND"
    sector_ids: list[str] = Field(default_factory=list, alias="sectorIds")
    metrics: list[MetricPredicateOutput] = Field(default_factory=list)
    entities: list[EntityRefOutput] = Field(default_factory=list)
    sort: list[SortItemOutput] = Field(default_factory=list)
    limit: int | None = None


class DecisionOutput(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    operator: Literal["AND", "OR", "NOT"] = "AND"
    groups: list[DecisionGroupOutput]
    sort: list[SortItemOutput] = Field(default_factory=list)
    limit: int = 20


def validate_against_candidate_contracts(decision: DecisionOutput | dict[str, Any], contract_index: dict[str, Any]) -> None:
    if isinstance(decision, dict):
        decision = DecisionOutput.model_validate(decision)

    for group in decision.groups:
        for sid in group.sector_ids:
            if sid not in contract_index or contract_index[sid].get("domain") != "scope":
                raise ValueError(f"Sector candidate {sid} not in candidate contracts")
        for m in group.metrics:
            if m.metric not in contract_index or contract_index[m.metric].get("domain") != "metric":
                raise ValueError(f"Metric candidate {m.metric} not in candidate contracts")
        for e in group.entities:
            if e.entity not in contract_index or contract_index[e.entity].get("domain") != "entity":
                raise ValueError(f"Entity candidate {e.entity} not in candidate contracts")
        for s in group.sort:
            if s.metric not in contract_index and s.metric != "fund_code":
                raise ValueError(f"Group sort metric candidate {s.metric} not in candidate contracts")
    for s in decision.sort:
        if s.metric not in contract_index and s.metric != "fund_code":
            raise ValueError(f"Sort metric candidate {s.metric} not in candidate contracts")
