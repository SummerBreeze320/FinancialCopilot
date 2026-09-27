from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Category(StrEnum):
    FUND_CODE = "fund_code"
    FUND_NAME = "fund_name"
    EXCHANGE_FUND_NAME = "exchange_fund_name"
    COMPANY_NAME = "company_name"
    MANAGER_NAME = "manager_name"
    TRACK_INDEX_NAME = "track_index_name"
    TRACK_INDEX_CODE = "track_index_code"
    STOCK_CODE = "stock_code"
    STOCK_NAME = "stock_name"
    BOND_CODE = "bond_code"
    BOND_NAME = "bond_name"


class MatchMethod(StrEnum):
    EXACT_CANONICAL = "exact_canonical"
    EXACT_ALIAS = "exact_alias"
    VECTOR = "vector"
    HYBRID = "hybrid"
    CONTEXT_RERANK = "context_rerank"


class Span(StrictModel):
    start: int = Field(ge=0)
    end: int = Field(ge=1)

    @model_validator(mode="after")
    def validate_order(self) -> "Span":
        if self.end <= self.start:
            raise ValueError("span.end must be greater than span.start")
        return self


class CatalogEntity(StrictModel):
    id: str = Field(min_length=1)
    category: Category
    canonical_value: str = Field(min_length=1)
    metadata: dict[str, Any]


class MatchEvidence(StrictModel):
    method: MatchMethod
    confidence: float = Field(ge=0, le=1)


class Candidate(StrictModel):
    rank: int = Field(ge=1)
    entity: CatalogEntity
    match: MatchEvidence


class Ambiguity(StrictModel):
    reason: str = Field(min_length=1)
    context_used: str
    context_needed: list[str]


class ResolvedMention(StrictModel):
    mention: str = Field(min_length=1)
    span: Span
    resolution: Literal["resolved"]
    entity: CatalogEntity
    match: MatchEvidence


class AmbiguousMention(StrictModel):
    mention: str = Field(min_length=1)
    span: Span
    resolution: Literal["ambiguous"]
    ambiguity: Ambiguity
    candidates: list[Candidate] = Field(min_length=2)


EntityMention = Annotated[
    ResolvedMention | AmbiguousMention,
    Field(discriminator="resolution"),
]


class UnresolvedMention(StrictModel):
    mention: str = Field(min_length=1)
    span: Span
    reason: Literal["no_catalog_candidate", "below_confidence_threshold"]


class RecognitionOptions(StrictModel):
    categories: list[Category] | None = None
    top_k: int = Field(default=20, ge=1, le=100)
    include_unresolved: bool = True


class RecognitionRequest(StrictModel):
    text: str = Field(min_length=1, max_length=20_000)
    options: RecognitionOptions = Field(default_factory=RecognitionOptions)


class Diagnostics(StrictModel):
    catalog_version: str = Field(min_length=1)
    entity_count: int = Field(ge=0)
    resolved_count: int = Field(ge=0)
    ambiguous_count: int = Field(ge=0)
    processing_ms: int = Field(ge=0)
    degraded_dependencies: list[Literal["embedding", "reranker"]]


class RecognitionResponse(StrictModel):
    request_id: str = Field(min_length=1)
    text: str = Field(min_length=1)
    status: Literal["completed", "degraded"]
    entities: list[EntityMention]
    unresolved_mentions: list[UnresolvedMention]
    diagnostics: Diagnostics


class RebuildRequest(StrictModel):
    catalog_path: str | None = None
    force: bool = False


class RebuildAccepted(StrictModel):
    job_id: str
    status: Literal["queued", "running"]


class ErrorBody(StrictModel):
    code: str
    message: str
    request_id: str
    details: dict[str, Any] = Field(default_factory=dict)


class ErrorResponse(StrictModel):
    error: ErrorBody
