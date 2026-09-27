from pathlib import Path
from types import SimpleNamespace

import pytest

from app.catalog.loader import CatalogLoader
from app.core.exact_matcher import ExactMatcher
from app.core.mention_generator import MentionGenerator
from app.core.pipeline import RecognitionPipeline
from app.core.resolver import CandidateFusion, Resolver
from app.db.repository import VectorHit
from app.models.api import Category, RecognitionRequest


FIXTURE = Path(__file__).parents[1] / "fixtures" / "entity-small.json"
DOCUMENT = CatalogLoader().load(FIXTURE)


class FakeEmbedding:
    def __init__(self, fail=False):
        self.fail = fail

    async def embed(self, texts):
        if self.fail:
            raise RuntimeError("embedding down")
        return [[float(index + 1), 0.0] for index, _ in enumerate(texts)]


class FakeRepository:
    def __init__(self, vector_entity=None):
        self.vector_entity = vector_entity

    async def get_active_version(self):
        return SimpleNamespace(version_id="v", catalog_version="test-v1", entity_count=len(DOCUMENT.entities))

    async def vector_search(self, embedding, top_k, categories=None, version_id=None):
        if self.vector_entity is None:
            return []
        if categories and self.vector_entity.category not in categories:
            return []
        return [VectorHit(version_id="v", entity=self.vector_entity, similarity=0.94)]


class IdentityReranker:
    async def rerank(self, mention, context, candidates):
        return candidates, False


class ContextReranker:
    async def rerank(self, mention, context, candidates):
        ranked = []
        for candidate in candidates:
            score = 0.98 if candidate.entity.category == Category.FUND_CODE else 0.70
            ranked.append(CandidateFusion.add_context(candidate, score))
        return sorted(ranked, key=lambda item: -item.confidence), False


def pipeline(repository=None, reranker=None, embedding=None):
    return RecognitionPipeline(
        matcher=ExactMatcher.build(DOCUMENT.entities),
        mention_generator=MentionGenerator(max_spans=32),
        embedding=embedding or FakeEmbedding(),
        repository=repository or FakeRepository(),
        reranker=reranker or IdentityReranker(),
        resolver=Resolver(0.55, 0.86, 0.10, 5),
        vector_min_similarity=0.68,
    )


@pytest.mark.asyncio
async def test_exact_name_resolves_with_original_span():
    response = await pipeline().recognize(RecognitionRequest(text="看看华夏成长"), "req-1")
    assert len(response.entities) == 1
    assert response.entities[0].entity.id == "fund_name:000001.OF"
    assert response.entities[0].mention == "华夏成长"
    assert response.entities[0].span.start == 2
    assert response.diagnostics.entity_count == 11


@pytest.mark.asyncio
async def test_shared_code_is_returned_as_cross_category_ambiguity():
    response = await pipeline().recognize(RecognitionRequest(text="比较000001最近表现"), "req-2")
    result = next(entity for entity in response.entities if entity.mention == "000001")
    assert result.resolution == "ambiguous"
    assert {candidate.entity.category for candidate in result.candidates} == {
        Category.FUND_CODE, Category.STOCK_CODE, Category.TRACK_INDEX_CODE
    }


@pytest.mark.asyncio
async def test_context_reranking_resolves_code_as_fund():
    response = await pipeline(reranker=ContextReranker()).recognize(
        RecognitionRequest(text="000001基金表现"), "req-3"
    )
    result = next(entity for entity in response.entities if entity.mention == "000001")
    assert result.resolution == "resolved"
    assert result.entity.category == Category.FUND_CODE


@pytest.mark.asyncio
async def test_fuzzy_recall_returns_only_repository_entity():
    target = DOCUMENT.by_id("track_index_name:沪深300")
    response = await pipeline(repository=FakeRepository(target)).recognize(
        RecognitionRequest(text="分析华夏沪深三百"), "req-4"
    )
    assert response.entities
    assert {entity.entity.id for entity in response.entities if entity.resolution == "resolved"} == {target.id}


@pytest.mark.asyncio
async def test_embedding_outage_keeps_exact_results_and_marks_degraded():
    response = await pipeline(embedding=FakeEmbedding(fail=True)).recognize(
        RecognitionRequest(text="华夏成长和未知标的"), "req-5"
    )
    assert any(entity.resolution == "resolved" and entity.entity.id == "fund_name:000001.OF"
               for entity in response.entities)
    assert response.status == "degraded"
    assert "embedding" in response.diagnostics.degraded_dependencies
