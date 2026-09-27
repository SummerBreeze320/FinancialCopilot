import json
from pathlib import Path

import pytest

from app.catalog.loader import CatalogError, CatalogLoader
from app.models.api import Category


FIXTURE = Path(__file__).parents[1] / "fixtures" / "entity-small.json"


def test_loader_maps_all_source_collections():
    document = CatalogLoader().load(FIXTURE)
    assert {item.category for item in document.entities} == set(Category)
    assert document.by_id("stock_code:000001.SZ").metadata["stock_name"] == "平安银行"


def test_loader_preserves_cross_category_aliases():
    document = CatalogLoader().load(FIXTURE)
    matches = [entity for entity in document.entities if "000001" in entity.normalized_aliases]
    assert {entity.category for entity in matches} == {
        Category.FUND_CODE,
        Category.STOCK_CODE,
        Category.TRACK_INDEX_CODE,
    }


def test_loader_rejects_conflicting_duplicate_ids(tmp_path):
    payload = json.loads(FIXTURE.read_text(encoding="utf-8"))
    payload["stock_names"].append({
        "id": "stock_name:平安银行",
        "name": "不同名称",
        "aliases": ["不同名称"],
        "metadata": {},
    })
    path = tmp_path / "bad.json"
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    with pytest.raises(CatalogError, match="conflicting duplicate id"):
        CatalogLoader().load(path)


def test_loader_hash_is_stable():
    first = CatalogLoader().load(FIXTURE)
    second = CatalogLoader().load(FIXTURE)
    assert first.sha256 == second.sha256
    assert len(first.sha256) == 64


def test_loader_counts_and_skips_malformed_entries(tmp_path):
    payload = json.loads(FIXTURE.read_text(encoding="utf-8"))
    payload["stock_names"].append({"id": "broken", "aliases": []})
    path = tmp_path / "partially-valid.json"
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    document = CatalogLoader().load(path)
    assert len(document.entities) == 11
    assert document.rejected_count == 1
