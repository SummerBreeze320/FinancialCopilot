from __future__ import annotations

import json
from pathlib import Path
import pytest

from app.config import Settings
from app.catalog.loader import Catalog


def _find_catalog_paths() -> tuple[Path, Path, Path]:
    service_root = Path(__file__).resolve().parents[2]
    repo_root = service_root
    while repo_root.parent != repo_root:
        if (repo_root / "docs" / "temp" / "metrics.json").exists():
            break
        repo_root = repo_root.parent

    metrics_path = repo_root / "docs" / "temp" / "metrics.json"
    if not metrics_path.exists():
        metrics_path = service_root / "tests" / "fixtures" / "metrics-small.json"

    sectors_path = repo_root / "docs" / "temp" / "sectors.json"
    if not sectors_path.exists():
        sectors_path = service_root / "tests" / "fixtures" / "sectors-small.json"

    entities_path = service_root / "tests" / "fixtures" / "entities-small.json"

    return metrics_path, sectors_path, entities_path


@pytest.fixture(scope="session")
def real_synced_metrics_json() -> Path:
    metrics_path, _, _ = _find_catalog_paths()
    return metrics_path


@pytest.fixture(scope="session")
def real_settings() -> Settings:
    metrics_path, sectors_path, entities_path = _find_catalog_paths()
    return Settings(
        metrics_catalog_path=str(metrics_path),
        sectors_catalog_path=str(sectors_path),
        entity_catalog_path=str(entities_path),
    )


@pytest.fixture(scope="session")
def real_candidates_catalog(real_settings: Settings) -> Catalog:
    return Catalog.from_files(
        Path(real_settings.metrics_catalog_path),
        Path(real_settings.sectors_catalog_path),
        Path(real_settings.entity_catalog_path),
    )
