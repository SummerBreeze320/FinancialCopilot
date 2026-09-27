from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from app.catalog.models import CatalogDocument, CatalogRecord
from app.core.normalization import normalize_alias
from app.models.api import Category


COLLECTIONS: dict[str, tuple[Category, str]] = {
    "fund_codes": (Category.FUND_CODE, "code"),
    "fund_names": (Category.FUND_NAME, "name"),
    "exchange_fund_names": (Category.EXCHANGE_FUND_NAME, "name"),
    "company_names": (Category.COMPANY_NAME, "name"),
    "manager_names": (Category.MANAGER_NAME, "name"),
    "track_index_names": (Category.TRACK_INDEX_NAME, "name"),
    "track_index_codes": (Category.TRACK_INDEX_CODE, "code"),
    "stock_codes": (Category.STOCK_CODE, "code"),
    "stock_names": (Category.STOCK_NAME, "name"),
    "bond_codes": (Category.BOND_CODE, "code"),
    "bond_names": (Category.BOND_NAME, "name"),
}


class CatalogError(ValueError):
    pass


class CatalogLoader:
    def load(self, path: str | Path) -> CatalogDocument:
        catalog_path = Path(path)
        raw = catalog_path.read_bytes()
        sha256 = hashlib.sha256(raw).hexdigest()
        try:
            payload = json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise CatalogError(f"invalid catalog JSON: {exc}") from exc

        records: dict[str, CatalogRecord] = {}
        for collection, (category, canonical_key) in COLLECTIONS.items():
            values = payload.get(collection, [])
            if not isinstance(values, list):
                raise CatalogError(f"{collection} must be an array")
            for index, item in enumerate(values):
                record = self._record(item, collection, index, category, canonical_key)
                existing = records.get(record.id)
                if existing is not None and existing != record:
                    raise CatalogError(f"conflicting duplicate id: {record.id}")
                records[record.id] = record

        if not records:
            raise CatalogError("catalog contains no valid entities")
        return CatalogDocument(
            version=str(payload.get("version", "unknown")),
            source=str(payload.get("source", "unknown")),
            sha256=sha256,
            entities=tuple(records.values()),
        )

    def _record(
        self,
        item: Any,
        collection: str,
        index: int,
        category: Category,
        canonical_key: str,
    ) -> CatalogRecord:
        if not isinstance(item, dict):
            raise CatalogError(f"{collection}[{index}] must be an object")
        entity_id = item.get("id")
        canonical = item.get(canonical_key)
        if not isinstance(entity_id, str) or not entity_id.strip():
            raise CatalogError(f"{collection}[{index}] has invalid id")
        if not isinstance(canonical, str) or not canonical.strip():
            raise CatalogError(f"{collection}[{index}] has invalid {canonical_key}")

        source_aliases = item.get("aliases", [])
        if not isinstance(source_aliases, list):
            raise CatalogError(f"{collection}[{index}].aliases must be an array")
        aliases = tuple(dict.fromkeys(
            value.strip() for value in [canonical, *source_aliases]
            if isinstance(value, str) and value.strip()
        ))
        normalized_aliases = tuple(dict.fromkeys(normalize_alias(value) for value in aliases))
        metadata = item.get("metadata", {})
        if not isinstance(metadata, dict):
            raise CatalogError(f"{collection}[{index}].metadata must be an object")
        metadata_text = "; ".join(f"{key}: {value}" for key, value in sorted(metadata.items()))
        search_text = f"类别: {category.value}; 标准值: {canonical}; 别名: {', '.join(aliases)}"
        if metadata_text:
            search_text += f"; 元数据: {metadata_text}"
        return CatalogRecord(
            id=entity_id,
            category=category,
            canonical_value=canonical.strip(),
            aliases=aliases,
            normalized_value=normalize_alias(canonical),
            normalized_aliases=normalized_aliases,
            metadata=dict(metadata),
            search_text=search_text,
        )
