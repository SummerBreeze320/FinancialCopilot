from __future__ import annotations

from pathlib import Path

import pytest
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from testcontainers.postgres import PostgresContainer

from app.catalog.loader import CatalogLoader
from app.db.models import Base
from app.db.repository import EntityRepository


pytestmark = pytest.mark.integration
FIXTURE = Path(__file__).parents[1] / "fixtures" / "entity-small.json"


@pytest.fixture(scope="module")
def postgres_url():
    try:
        with PostgresContainer("pgvector/pgvector:pg16") as postgres:
            yield postgres.get_connection_url().replace("psycopg2", "asyncpg")
    except Exception as exc:
        pytest.skip(f"Docker/PostgreSQL unavailable: {exc}")


@pytest.fixture
async def repository(postgres_url):
    engine = create_async_engine(postgres_url)
    async with engine.begin() as connection:
        await connection.exec_driver_sql("CREATE EXTENSION IF NOT EXISTS vector")
        await connection.run_sync(Base.metadata.create_all)
    repo = EntityRepository(async_sessionmaker(engine, expire_on_commit=False))
    yield repo
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.drop_all)
    await engine.dispose()


async def seed_version(repository: EntityRepository, suffix: str, active: bool = False):
    document = CatalogLoader().load(FIXTURE)
    version_id = await repository.create_version(
        catalog_version=f"test-{suffix}", source="test", source_sha256=document.sha256 + suffix
    )
    record = document.entities[0]
    await repository.insert_batch(version_id, [(record, [1.0] + [0.0] * 1023)])
    if active:
        await repository.activate_version(version_id)
    return version_id, record


async def test_activation_is_atomic(repository):
    version_a, _ = await seed_version(repository, "a", active=True)
    version_b, _ = await seed_version(repository, "b")
    await repository.activate_version(version_b)
    assert (await repository.get_active_version()).version_id == version_b
    assert await repository.version_status(version_a) == "retired"


async def test_vector_search_isolated_to_active_version(repository):
    inactive, _ = await seed_version(repository, "old")
    active, record = await seed_version(repository, "new", active=True)
    hits = await repository.vector_search([1.0] + [0.0] * 1023, top_k=5)
    assert hits[0].entity.id == record.id
    assert {hit.version_id for hit in hits} == {active}
    assert inactive not in {hit.version_id for hit in hits}
