from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from app.models.api import Category


@dataclass(frozen=True, slots=True)
class CatalogRecord:
    id: str
    category: Category
    canonical_value: str
    aliases: tuple[str, ...]
    normalized_value: str
    normalized_aliases: tuple[str, ...]
    metadata: dict[str, Any]
    search_text: str


@dataclass(frozen=True, slots=True)
class CatalogDocument:
    version: str
    source: str
    sha256: str
    entities: tuple[CatalogRecord, ...]
    rejected_count: int = 0

    def by_id(self, entity_id: str) -> CatalogRecord:
        for entity in self.entities:
            if entity.id == entity_id:
                return entity
        raise KeyError(entity_id)
