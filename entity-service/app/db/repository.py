from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import uuid

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.catalog.models import CatalogRecord
from app.db.models import CatalogItemRow, CatalogVersionRow
from app.models.api import Category


@dataclass(frozen=True, slots=True)
class VersionInfo:
    version_id: uuid.UUID
    catalog_version: str
    source: str
    source_sha256: str
    entity_count: int
    status: str


@dataclass(frozen=True, slots=True)
class VectorHit:
    version_id: uuid.UUID
    entity: CatalogRecord
    similarity: float


class EntityRepository:
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._sessions = session_factory

    async def create_version(self, catalog_version: str, source: str, source_sha256: str) -> uuid.UUID:
        version_id = uuid.uuid4()
        async with self._sessions.begin() as session:
            session.add(CatalogVersionRow(
                version_id=version_id,
                catalog_version=catalog_version,
                source=source,
                source_sha256=source_sha256,
                entity_count=0,
                status="building",
            ))
        return version_id

    async def find_version_by_sha(self, source_sha256: str) -> VersionInfo | None:
        async with self._sessions() as session:
            row = await session.scalar(select(CatalogVersionRow).where(
                CatalogVersionRow.source_sha256 == source_sha256,
                CatalogVersionRow.status == "active",
            ))
            return self._version(row) if row else None

    async def verify_version(self, version_id: uuid.UUID, expected_count: int, expected_categories: set[Category]) -> None:
        from sqlalchemy import func
        async with self._sessions() as session:
            count = await session.scalar(select(func.count()).select_from(CatalogItemRow).where(
                CatalogItemRow.version_id == version_id
            ))
            categories = set((await session.scalars(select(CatalogItemRow.category).where(
                CatalogItemRow.version_id == version_id
            ).distinct())).all())
        expected = {category.value for category in expected_categories}
        if count != expected_count or categories != expected:
            raise ValueError(
                f"catalog verification failed: rows={count}/{expected_count}, categories={sorted(categories)}/{sorted(expected)}"
            )

    async def insert_batch(
        self,
        version_id: uuid.UUID,
        records: list[tuple[CatalogRecord, list[float]]],
    ) -> None:
        async with self._sessions.begin() as session:
            session.add_all([
                CatalogItemRow(
                    version_id=version_id,
                    entity_id=record.id,
                    category=record.category.value,
                    canonical_value=record.canonical_value,
                    normalized_value=record.normalized_value,
                    aliases=list(record.aliases),
                    normalized_aliases=list(record.normalized_aliases),
                    metadata_json=record.metadata,
                    search_text=record.search_text,
                    embedding=embedding,
                )
                for record, embedding in records
            ])
            await session.execute(
                update(CatalogVersionRow)
                .where(CatalogVersionRow.version_id == version_id)
                .values(entity_count=CatalogVersionRow.entity_count + len(records))
            )

    async def activate_version(self, version_id: uuid.UUID) -> None:
        async with self._sessions.begin() as session:
            target = await session.scalar(
                select(CatalogVersionRow).where(CatalogVersionRow.version_id == version_id).with_for_update()
            )
            if target is None:
                raise KeyError(str(version_id))
            if target.status not in {"building", "active"}:
                raise ValueError(f"cannot activate version in status {target.status}")
            await session.execute(
                update(CatalogVersionRow)
                .where(CatalogVersionRow.status == "active", CatalogVersionRow.version_id != version_id)
                .values(status="retired")
            )
            target.status = "active"
            target.activated_at = datetime.now(timezone.utc)

    async def fail_version(self, version_id: uuid.UUID) -> None:
        async with self._sessions.begin() as session:
            await session.execute(
                update(CatalogVersionRow)
                .where(CatalogVersionRow.version_id == version_id, CatalogVersionRow.status == "building")
                .values(status="failed")
            )

    async def get_active_version(self) -> VersionInfo | None:
        async with self._sessions() as session:
            row = await session.scalar(select(CatalogVersionRow).where(CatalogVersionRow.status == "active"))
            return self._version(row) if row else None

    async def version_status(self, version_id: uuid.UUID) -> str | None:
        async with self._sessions() as session:
            return await session.scalar(
                select(CatalogVersionRow.status).where(CatalogVersionRow.version_id == version_id)
            )

    async def get_entity(self, entity_id: str) -> CatalogRecord | None:
        async with self._sessions() as session:
            row = await session.scalar(
                select(CatalogItemRow)
                .join(CatalogVersionRow, CatalogItemRow.version_id == CatalogVersionRow.version_id)
                .where(CatalogVersionRow.status == "active", CatalogItemRow.entity_id == entity_id)
            )
            return self._record(row) if row else None

    async def list_active_entities(self) -> tuple[CatalogRecord, ...]:
        async with self._sessions() as session:
            rows = (await session.scalars(
                select(CatalogItemRow)
                .join(CatalogVersionRow, CatalogItemRow.version_id == CatalogVersionRow.version_id)
                .where(CatalogVersionRow.status == "active")
            )).all()
            return tuple(self._record(row) for row in rows)

    async def vector_search(
        self,
        embedding: list[float],
        top_k: int,
        categories: list[Category] | None = None,
    ) -> list[VectorHit]:
        distance = CatalogItemRow.embedding.cosine_distance(embedding)
        statement = (
            select(CatalogItemRow, distance.label("distance"))
            .join(CatalogVersionRow, CatalogItemRow.version_id == CatalogVersionRow.version_id)
            .where(CatalogVersionRow.status == "active")
            .order_by(distance)
            .limit(top_k)
        )
        if categories:
            statement = statement.where(CatalogItemRow.category.in_([value.value for value in categories]))
        async with self._sessions() as session:
            rows = (await session.execute(statement)).all()
            return [VectorHit(
                version_id=row.CatalogItemRow.version_id,
                entity=self._record(row.CatalogItemRow),
                similarity=max(0.0, min(1.0, 1.0 - float(row.distance))),
            ) for row in rows]

    @staticmethod
    def _version(row: CatalogVersionRow) -> VersionInfo:
        return VersionInfo(
            version_id=row.version_id,
            catalog_version=row.catalog_version,
            source=row.source,
            source_sha256=row.source_sha256,
            entity_count=row.entity_count,
            status=row.status,
        )

    @staticmethod
    def _record(row: CatalogItemRow) -> CatalogRecord:
        return CatalogRecord(
            id=row.entity_id,
            category=Category(row.category),
            canonical_value=row.canonical_value,
            aliases=tuple(row.aliases),
            normalized_value=row.normalized_value,
            normalized_aliases=tuple(row.normalized_aliases),
            metadata=dict(row.metadata_json),
            search_text=row.search_text,
        )
