from __future__ import annotations

from datetime import datetime
import uuid

from pgvector.sqlalchemy import Vector
from sqlalchemy import DateTime, ForeignKey, Index, Integer, JSON, String, Text, Uuid, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class CatalogVersionRow(Base):
    __tablename__ = "entity_catalog_version"

    version_id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    catalog_version: Mapped[str] = mapped_column(Text, nullable=False)
    source: Mapped[str] = mapped_column(Text, nullable=False)
    source_sha256: Mapped[str] = mapped_column(Text, nullable=False, unique=True)
    entity_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    status: Mapped[str] = mapped_column(String(16), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    activated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    __table_args__ = (
        Index(
            "uq_entity_catalog_single_active",
            "status",
            unique=True,
            postgresql_where=(status == "active"),
        ),
    )


class CatalogItemRow(Base):
    __tablename__ = "entity_catalog_item"

    version_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("entity_catalog_version.version_id", ondelete="CASCADE"), primary_key=True
    )
    entity_id: Mapped[str] = mapped_column(Text, primary_key=True)
    category: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    canonical_value: Mapped[str] = mapped_column(Text, nullable=False)
    normalized_value: Mapped[str] = mapped_column(Text, nullable=False, index=True)
    aliases: Mapped[list[str]] = mapped_column(JSON, nullable=False)
    normalized_aliases: Mapped[list[str]] = mapped_column(JSON, nullable=False)
    metadata_json: Mapped[dict] = mapped_column("metadata", JSON, nullable=False)
    search_text: Mapped[str] = mapped_column(Text, nullable=False)
    embedding: Mapped[list[float]] = mapped_column(Vector(1024), nullable=False)

    __table_args__ = (Index("idx_entity_item_version_category", "version_id", "category"),)
