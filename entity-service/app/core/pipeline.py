from __future__ import annotations

from collections import defaultdict
import time

from app.core.normalization import normalize_with_offsets
from app.core.resolver import CandidateFusion, ScoredCandidate
from app.models.api import (
    Diagnostics,
    RecognitionRequest,
    RecognitionResponse,
    Span,
    UnresolvedMention,
)


class IndexNotReadyError(RuntimeError):
    pass


class RecognitionPipeline:
    def __init__(
        self,
        matcher,
        mention_generator,
        embedding,
        repository,
        reranker,
        resolver,
        vector_min_similarity: float,
    ) -> None:
        self._matcher = matcher
        self._mention_generator = mention_generator
        self._embedding = embedding
        self._repository = repository
        self._reranker = reranker
        self._resolver = resolver
        self._vector_min_similarity = vector_min_similarity

    async def recognize(self, request: RecognitionRequest, request_id: str) -> RecognitionResponse:
        started = time.perf_counter_ns()
        version = await self._repository.get_active_version()
        if version is None:
            raise IndexNotReadyError("No active entity catalog index is available")

        normalized = normalize_with_offsets(request.text)
        category_filter = set(request.options.categories) if request.options.categories else None
        groups: dict[tuple[int, int, str], list[ScoredCandidate]] = defaultdict(list)
        exact_hits = self._matcher.find(normalized)
        covered: list[Span] = []
        for hit in exact_hits:
            if category_filter is not None and hit.entity.category not in category_filter:
                continue
            key = (hit.span.start, hit.span.end, hit.mention)
            groups[key].append(CandidateFusion.exact(hit.entity, hit.exact_canonical))
            covered.append(hit.span)

        generated = self._mention_generator.generate(normalized, covered)
        degraded: set[str] = set()
        unresolved: list[UnresolvedMention] = []
        if generated:
            try:
                vectors = await self._embedding.embed([item.normalized_text for item in generated])
                for item, vector in zip(generated, vectors, strict=True):
                    hits = await self._repository.vector_search(
                        vector,
                        top_k=request.options.top_k,
                        categories=request.options.categories,
                    )
                    valid_hits = [hit for hit in hits if hit.similarity >= self._vector_min_similarity]
                    if valid_hits:
                        key = (item.span.start, item.span.end, item.mention)
                        groups[key].extend(
                            CandidateFusion.vector(hit.entity, hit.similarity) for hit in valid_hits
                        )
                    elif request.options.include_unresolved:
                        unresolved.append(UnresolvedMention(
                            mention=item.mention,
                            span=item.span,
                            reason="no_catalog_candidate",
                        ))
            except Exception:
                degraded.add("embedding")
                if request.options.include_unresolved:
                    unresolved.extend(UnresolvedMention(
                        mention=item.mention,
                        span=item.span,
                        reason="no_catalog_candidate",
                    ) for item in generated)

        entities = []
        for (start, end, mention), candidates in groups.items():
            ranked = candidates
            if len({candidate.entity.id for candidate in candidates}) > 1:
                try:
                    ranked, reranker_degraded = await self._reranker.rerank(mention, request.text, candidates)
                    if reranker_degraded:
                        degraded.add("reranker")
                except Exception:
                    degraded.add("reranker")
            result = self._resolver.resolve(mention, Span(start=start, end=end), ranked, request.text)
            if result is not None:
                entities.append(result)
            elif request.options.include_unresolved:
                unresolved.append(UnresolvedMention(
                    mention=mention,
                    span=Span(start=start, end=end),
                    reason="below_confidence_threshold",
                ))

        entities.sort(key=lambda item: (item.span.start, item.span.end, item.resolution))
        unique_unresolved = {
            (item.span.start, item.span.end, item.mention, item.reason): item for item in unresolved
        }
        unresolved = sorted(unique_unresolved.values(), key=lambda item: (item.span.start, item.span.end))
        resolved_count = sum(item.resolution == "resolved" for item in entities)
        ambiguous_count = sum(item.resolution == "ambiguous" for item in entities)
        elapsed_ms = max(0, (time.perf_counter_ns() - started) // 1_000_000)
        return RecognitionResponse(
            request_id=request_id,
            text=request.text,
            status="degraded" if degraded else "completed",
            entities=entities,
            unresolved_mentions=unresolved,
            diagnostics=Diagnostics(
                catalog_version=version.catalog_version,
                entity_count=len(entities),
                resolved_count=resolved_count,
                ambiguous_count=ambiguous_count,
                processing_ms=elapsed_ms,
                degraded_dependencies=sorted(degraded),
            ),
        )
