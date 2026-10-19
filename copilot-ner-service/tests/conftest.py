from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.api.dependencies import get_indexing_service, get_pipeline, get_repository
from app.main import app
from app.models.api import (
    CatalogEntity,
    Diagnostics,
    MatchEvidence,
    MatchMethod,
    RecognitionResponse,
    ResolvedMention,
    Span,
)


class FakePipeline:
    async def recognize(self, request, request_id):
        return RecognitionResponse(
            request_id=request_id,
            text=request.text,
            status="completed",
            entities=[ResolvedMention(
                mention="华夏成长",
                span=Span(start=0, end=4),
                resolution="resolved",
                entity=CatalogEntity(id="fund_name:000001.OF", category="fund_name",
                                     canonical_value="华夏成长", metadata={"fund_code": "000001.OF"}),
                match=MatchEvidence(method=MatchMethod.EXACT_ALIAS, confidence=0.99),
            )],
            unresolved_mentions=[],
            diagnostics=Diagnostics(catalog_version="test-v1", entity_count=1, resolved_count=1,
                                    ambiguous_count=0, processing_ms=1, degraded_dependencies=[]),
        )


class FakeRepository:
    async def get_entity(self, entity_id):
        if entity_id != "fund_name:000001.OF":
            return None
        return SimpleNamespace(id=entity_id, category="fund_name", canonical_value="华夏成长",
                               metadata={"fund_code": "000001.OF"})

    async def get_active_version(self):
        return SimpleNamespace(catalog_version="test-v1", entity_count=11)


class FakeIndexing:
    async def start_rebuild(self, path, force=False):
        return SimpleNamespace(job_id="job-1", status="queued", processed=0, total=0,
                               version_id=None, error=None)

    def get_job(self, job_id):
        if job_id == "job-1":
            return SimpleNamespace(job_id="job-1", status="completed", processed=11, total=11,
                                   version_id="version-1", error=None)
        return None


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("ENTITY_ADMIN_TOKEN", "test-token")
    from app.config import get_settings
    get_settings.cache_clear()
    app.dependency_overrides[get_pipeline] = lambda: FakePipeline()
    app.dependency_overrides[get_repository] = lambda: FakeRepository()
    app.dependency_overrides[get_indexing_service] = lambda: FakeIndexing()
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()
    get_settings.cache_clear()
