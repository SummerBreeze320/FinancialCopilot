import asyncio
from pathlib import Path
import uuid

import pytest

from app.catalog.loader import CatalogLoader
from app.indexing.service import IndexingService, RebuildAlreadyRunning


FIXTURE = Path(__file__).parents[1] / "fixtures" / "entity-small.json"
pytestmark = pytest.mark.integration


class FakeEmbedding:
    def __init__(self, fail: bool = False):
        self.fail = fail

    async def embed(self, texts):
        if self.fail:
            raise RuntimeError("embedding unavailable")
        return [[1.0] + [0.0] * 1023 for _ in texts]


class FakeRepository:
    def __init__(self):
        self.versions = {}
        self.active = None
        self.inserted = []

    async def find_version_by_sha(self, sha):
        return next((v for v in self.versions.values() if v.source_sha256 == sha and v.status == "active"), None)

    async def create_version(self, catalog_version, source, source_sha256):
        version_id = uuid.uuid4()
        self.versions[version_id] = type("Version", (), {
            "version_id": version_id, "source_sha256": source_sha256, "status": "building"
        })()
        return version_id

    async def insert_batch(self, version_id, records):
        self.inserted.extend(records)

    async def activate_version(self, version_id):
        if self.active:
            self.versions[self.active].status = "retired"
        self.active = version_id
        self.versions[version_id].status = "active"

    async def fail_version(self, version_id):
        self.versions[version_id].status = "failed"


@pytest.mark.asyncio
async def test_rebuild_activates_only_after_all_entities_are_inserted():
    repository = FakeRepository()
    service = IndexingService(repository, CatalogLoader(), FakeEmbedding(), batch_size=4)
    job = await service.start_rebuild(FIXTURE)
    result = await service.wait(job.job_id)
    assert result.status == "completed"
    assert result.processed == 11
    assert len(repository.inserted) == 11
    assert repository.active is not None


@pytest.mark.asyncio
async def test_failed_embedding_does_not_activate_version():
    repository = FakeRepository()
    service = IndexingService(repository, CatalogLoader(), FakeEmbedding(fail=True), batch_size=4)
    job = await service.start_rebuild(FIXTURE)
    result = await service.wait(job.job_id)
    assert result.status == "failed"
    assert repository.active is None
    assert {version.status for version in repository.versions.values()} == {"failed"}


@pytest.mark.asyncio
async def test_rejects_concurrent_rebuild():
    gate = asyncio.Event()

    class BlockingEmbedding(FakeEmbedding):
        async def embed(self, texts):
            await gate.wait()
            return await super().embed(texts)

    service = IndexingService(FakeRepository(), CatalogLoader(), BlockingEmbedding(), batch_size=4)
    first = await service.start_rebuild(FIXTURE)
    with pytest.raises(RebuildAlreadyRunning):
        await service.start_rebuild(FIXTURE)
    gate.set()
    await service.wait(first.job_id)


@pytest.mark.asyncio
async def test_successful_rebuild_refreshes_in_memory_catalog():
    refreshed = []

    async def activate(version_id, document, records):
        await repository.activate_version(version_id)
        refreshed.append(tuple(records))

    repository = FakeRepository()
    service = IndexingService(repository, CatalogLoader(), FakeEmbedding(), batch_size=4, activator=activate)
    job = await service.start_rebuild(FIXTURE)
    result = await service.wait(job.job_id)
    assert result.status == "completed"
    assert len(refreshed) == 1
    assert len(refreshed[0]) == 11


@pytest.mark.asyncio
async def test_failed_prepared_activation_never_activates_new_version():
    repository = FakeRepository()

    async def failing_activator(version_id, document, records):
        raise MemoryError("matcher build failed")

    service = IndexingService(
        repository, CatalogLoader(), FakeEmbedding(), activator=failing_activator
    )
    job = await service.start_rebuild(FIXTURE)
    result = await service.wait(job.job_id)
    assert result.status == "failed"
    assert repository.active is None
    assert {version.status for version in repository.versions.values()} == {"failed"}
