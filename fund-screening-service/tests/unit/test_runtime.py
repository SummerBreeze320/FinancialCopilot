from pathlib import Path

from app.config import Settings
from app.runtime import build_production_compiler


def test_local_model_clients_do_not_inherit_system_proxy(monkeypatch, tmp_path: Path):
    metrics = tmp_path / "metrics.json"
    sectors = tmp_path / "sectors.json"
    entities = tmp_path / "entities.json"
    metrics.write_text("[]", encoding="utf-8")
    sectors.write_text("[]", encoding="utf-8")
    entities.write_text("[]", encoding="utf-8")
    calls = []

    class Client:
        def __init__(self, **kwargs):
            calls.append(kwargs)

    monkeypatch.setattr("app.runtime.httpx.AsyncClient", Client)
    build_production_compiler(Settings(
        metrics_catalog_path=str(metrics),
        sectors_catalog_path=str(sectors),
        entity_catalog_path=str(entities),
    ))

    assert len(calls) == 2
    assert all(call["trust_env"] is False for call in calls)
