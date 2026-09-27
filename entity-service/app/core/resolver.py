from __future__ import annotations

from dataclasses import dataclass, replace
from difflib import SequenceMatcher
import re

from app.catalog.models import CatalogRecord
from app.models.api import (
    Ambiguity,
    AmbiguousMention,
    Candidate,
    CatalogEntity,
    MatchEvidence,
    MatchMethod,
    ResolvedMention,
    Span,
)


@dataclass(frozen=True, slots=True)
class ScoredCandidate:
    entity: CatalogRecord
    confidence: float
    method: MatchMethod


class CandidateFusion:
    @staticmethod
    def exact(record: CatalogRecord, canonical: bool) -> ScoredCandidate:
        return ScoredCandidate(
            entity=record,
            confidence=0.995 if canonical else 0.97,
            method=MatchMethod.EXACT_CANONICAL if canonical else MatchMethod.EXACT_ALIAS,
        )

    @staticmethod
    def vector(record: CatalogRecord, similarity: float, mention: str = "", context: str = "") -> ScoredCandidate:
        lexical = max(
            (SequenceMatcher(None, mention.casefold(), alias).ratio() for alias in record.normalized_aliases),
            default=0.0,
        )
        code_compatible = 1.0 if bool(re.search(r"\d", mention)) == record.category.value.endswith("code") else 0.0
        category_terms = {
            "fund": ("基金",), "stock": ("股票", "个股"),
            "index": ("指数",), "bond": ("债", "转债"),
        }
        family = next((name for name in category_terms if name in record.category.value), "")
        contextual = 1.0 if family and any(term in context for term in category_terms[family]) else 0.0
        confidence = similarity * 0.90 + lexical * 0.06 + code_compatible * 0.02 + contextual * 0.02
        confidence = max(0.0, min(1.0, confidence))
        return ScoredCandidate(entity=record, confidence=confidence, method=MatchMethod.VECTOR)

    @staticmethod
    def add_context(candidate: ScoredCandidate, confidence: float) -> ScoredCandidate:
        return replace(candidate, confidence=max(0.0, min(1.0, confidence)), method=MatchMethod.CONTEXT_RERANK)


class Resolver:
    def __init__(
        self,
        return_min_confidence: float,
        resolved_min_confidence: float,
        resolved_min_margin: float,
        ambiguous_max_candidates: int,
    ) -> None:
        self.return_min_confidence = return_min_confidence
        self.resolved_min_confidence = resolved_min_confidence
        self.resolved_min_margin = resolved_min_margin
        self.ambiguous_max_candidates = ambiguous_max_candidates

    def resolve(self, mention: str, span: Span, candidates: list[ScoredCandidate], context: str):
        best_by_id: dict[str, ScoredCandidate] = {}
        for candidate in candidates:
            existing = best_by_id.get(candidate.entity.id)
            if existing is None or candidate.confidence > existing.confidence:
                best_by_id[candidate.entity.id] = candidate
        ranked = sorted(
            (candidate for candidate in best_by_id.values() if candidate.confidence >= self.return_min_confidence),
            key=lambda candidate: (-candidate.confidence, candidate.entity.id),
        )
        if not ranked:
            return None

        margin = ranked[0].confidence - ranked[1].confidence if len(ranked) > 1 else 1.0
        if ranked[0].confidence >= self.resolved_min_confidence and margin >= self.resolved_min_margin:
            return ResolvedMention(
                mention=mention,
                span=span,
                resolution="resolved",
                entity=self._entity(ranked[0].entity),
                match=MatchEvidence(method=ranked[0].method, confidence=ranked[0].confidence),
            )
        if len(ranked) < 2:
            return None

        ranked = ranked[:self.ambiguous_max_candidates]
        categories = [candidate.entity.category.value for candidate in ranked]
        category_names = "、".join(dict.fromkeys(categories))
        return AmbiguousMention(
            mention=mention,
            span=span,
            resolution="ambiguous",
            ambiguity=Ambiguity(
                reason="多个目录实体得分接近，当前上下文不足以唯一确定",
                context_used=context,
                context_needed=[f"需要补充信息以区分：{category_names}"],
            ),
            candidates=[Candidate(
                rank=index,
                entity=self._entity(candidate.entity),
                match=MatchEvidence(method=candidate.method, confidence=candidate.confidence),
            ) for index, candidate in enumerate(ranked, 1)],
        )

    @staticmethod
    def _entity(record: CatalogRecord) -> CatalogEntity:
        return CatalogEntity(
            id=record.id,
            category=record.category,
            canonical_value=record.canonical_value,
            metadata=record.metadata,
        )
