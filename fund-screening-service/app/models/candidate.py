from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class CandidateContract:
    domain: str
    key: str
    name: str
    aliases: tuple[str, ...] = ()
    supported_operators: tuple[str, ...] = (">", ">=", "<", "<=", "=", "!=")
    value_type: str = "NUMBER"
    supported_usage: tuple[str, ...] = ("filter",)
    binding_role: str | None = None
    preferred_metric: str | None = None
    score: float = 1.0
    parameters: tuple[Any, ...] = ()
    result_contract: dict[str, Any] = field(default_factory=dict)
    parameter_schema_version: int = 1
    contract_status: str = "INFERRED"
    bindings: tuple[dict[str, Any], ...] = ()
    default_priority: int = 0


from pydantic import BaseModel, ConfigDict, Field
from typing import Literal


class ConditionCandidateView(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    condition_id: str = Field(alias="conditionId")
    candidate_type: Literal["metric", "scope", "entity"] = Field(alias="candidateType")
    canonical_id: str = Field(alias="canonicalId")
    display_name: str = Field(alias="displayName")
    aliases_matched: list[str] = Field(default_factory=list, alias="aliasesMatched")
    retrieval_channel: str = Field(alias="retrievalChannel")
    retrieval_score: float = Field(default=0.0, alias="retrievalScore")
    rank: int = Field(default=1)
    parameter_schema: list[dict[str, Any]] = Field(default_factory=list, alias="parameterSchema")
    storage_convention: str | None = Field(default=None, alias="storageConvention")

