from __future__ import annotations

from typing import Annotated, Any, Literal, Union
from pydantic import BaseModel, ConfigDict, Field, model_validator



class ScopeLeaf(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    condition_id: str = Field(alias="conditionId")
    mention: str
    domain: Literal["scope"] = "scope"


class ScopeTree(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    operator: Literal["AND", "OR", "NOT"] = "OR"
    children: list[ScopeTree | ScopeLeaf] = Field(default_factory=list)


class TimeHint(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    type: Literal["TRAILING", "CALENDAR_QUARTER", "YEAR_TO_DATE", "FIXED_QUARTER"]
    value: int | None = None
    unit: Literal["MONTH", "YEAR", "DAY", "WEEK"] | None = None
    offset: int | None = None
    year: int | None = None
    quarter: int | None = None


class MetricLeaf(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    condition_id: str = Field(alias="conditionId")
    mention: str
    domain: Literal["metric", "entity"] = "metric"
    operator_hint: Literal[">", ">=", "<", "<=", "=", "!=", ""] | None = Field(default=None, alias="operatorHint")
    value_hint: Any | None = Field(default=None, alias="valueHint")
    unit_hint: str | None = Field(default=None, alias="unitHint")
    time_hint: TimeHint | None = Field(default=None, alias="timeHint")


class MetricTree(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    operator: Literal["AND", "OR", "NOT"] = "AND"
    children: list[MetricTree | MetricLeaf] = Field(default_factory=list)


class SortMention(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    mention: str
    direction: Literal["ASC", "DESC"] = "DESC"
    level: Literal["ROOT", "GROUP"] = "ROOT"
    group_id: str | None = Field(default=None, alias="groupId")



class PlanGroup(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    group_id: str = Field(default="g1", alias="groupId")
    scope_tree: ScopeTree = Field(default_factory=ScopeTree, alias="scopeTree")
    metric_tree: MetricTree = Field(default_factory=MetricTree, alias="metricTree")


class SemanticPlan(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    intent: Literal["CREATE", "EDIT"] = "CREATE"
    group_operator: Literal["AND", "OR"] = Field(default="AND", alias="groupOperator")
    groups: list[PlanGroup] = Field(default_factory=list)
    sort_mentions: list[SortMention] = Field(default_factory=list, alias="sortMentions")
    limit: int = 20


class BaseConditionBinding(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    condition_id: str = Field(alias="conditionId")
    candidate_key: str = Field(alias="candidateKey")
    confidence: float = 1.0

    @model_validator(mode="before")
    @classmethod
    def _normalize_keys(cls, data: Any) -> Any:
        if isinstance(data, dict):
            data = dict(data)
            if "candidateKey" not in data and "candidate_key" not in data:
                if "selectedCandidateId" in data:
                    data["candidateKey"] = data["selectedCandidateId"]
                elif "selected_candidate_id" in data:
                    data["candidateKey"] = data["selected_candidate_id"]
            if "conditionId" not in data and "condition_id" not in data:
                if "condition_id" in data:
                    data["conditionId"] = data["condition_id"]
        return data

    @property
    def selected_candidate_id(self) -> str:
        return self.candidate_key

    @property
    def selectedCandidateId(self) -> str:
        return self.candidate_key

    @property
    def candidateKey(self) -> str:
        return self.candidate_key

    @property
    def conditionId(self) -> str:
        return self.condition_id


class MetricConditionBinding(BaseConditionBinding):
    domain: Literal["metric"] = "metric"
    operator: Literal[">", ">=", "<", "<=", "=", "!="] = "="
    value: Any = ...
    unit: str | None = None
    period: str | None = None
    params: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def validate_metric_value(self) -> MetricConditionBinding:
        if self.value is None:
            raise ValueError(f"metric condition {self.condition_id} requires a non-null value")
        return self


class ScopeConditionBinding(BaseConditionBinding):
    domain: Literal["scope"] = "scope"
    operator: Literal[">", ">=", "<", "<=", "=", "!="] = "="
    value: Any = None
    sector_id: str | None = Field(default=None, alias="sectorId")
    params: dict[str, Any] = Field(default_factory=dict)


class EntityConditionBinding(BaseConditionBinding):
    domain: Literal["entity"] = "entity"
    binding_role: str | None = Field(default=None, alias="bindingRole")
    operator: Literal[">", ">=", "<", "<=", "=", "!="] = "="
    value: Any = None
    unit: str | None = None
    params: dict[str, Any] = Field(default_factory=dict)


ConditionBindingType = Annotated[
    Union[MetricConditionBinding, ScopeConditionBinding, EntityConditionBinding],
    Field(discriminator="domain"),
]


class _ConditionBindingMeta(type):
    def __call__(cls, *args, **kwargs):
        domain = kwargs.get("domain")
        if not domain:
            if "sectorId" in kwargs or "sector_id" in kwargs:
                domain = "scope"
            elif kwargs.get("binding_role") or kwargs.get("bindingRole"):
                domain = "entity"
            elif any(k in str(kwargs.get("candidate_key") or kwargs.get("candidateKey") or "") for k in ("company", "manager", "stock", "index")):
                domain = "entity"
            elif any(len(str(c)) == 16 and str(c).isdigit() for c in [kwargs.get("candidate_key") or kwargs.get("candidateKey") or ""]):
                domain = "scope"
            elif "value" in kwargs and kwargs["value"] is not None:
                domain = "metric"
            else:
                domain = "metric"
            kwargs["domain"] = domain

        if domain == "scope":
            return ScopeConditionBinding(*args, **kwargs)
        elif domain == "entity":
            return EntityConditionBinding(*args, **kwargs)
        else:
            return MetricConditionBinding(*args, **kwargs)

    def __instancecheck__(cls, instance: Any) -> bool:
        return isinstance(instance, (MetricConditionBinding, ScopeConditionBinding, EntityConditionBinding))

    @classmethod
    def __get_pydantic_core_schema__(cls, source_type, handler):
        return handler.generate_schema(ConditionBindingType)


class ConditionBinding(metaclass=_ConditionBindingMeta):
    pass


ResolvedCondition = ConditionBinding


class SortBinding(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    mention: str
    candidate_key: str = Field(alias="candidateKey")
    direction: Literal["ASC", "DESC"] = "DESC"
    level: Literal["ROOT", "GROUP"] = "ROOT"
    group_id: str | None = Field(default=None, alias="groupId")


class ClarificationOutput(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    condition_id: str | None = Field(default=None, alias="conditionId")
    code: str
    message: str


class AdjudicationResult(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    condition_bindings: list[ConditionBindingType] = Field(default_factory=list, alias="conditionBindings")
    sort_bindings: list[SortBinding] = Field(default_factory=list, alias="sortBindings")
    clarifications: list[ClarificationOutput] = Field(default_factory=list)

    @model_validator(mode="before")
    @classmethod
    def _ensure_domains(cls, data: Any) -> Any:
        if isinstance(data, dict):
            bindings = data.get("conditionBindings") or data.get("condition_bindings")
            if isinstance(bindings, list):
                for b in bindings:
                    if isinstance(b, dict) and "domain" not in b:
                        b["domain"] = "metric"
        return data
