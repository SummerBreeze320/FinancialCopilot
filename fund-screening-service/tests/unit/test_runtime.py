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
    import httpx
    calls = []
    orig_init = httpx.AsyncClient.__init__

    def spy_init(self, *args, **kwargs):
        calls.append(kwargs)
        orig_init(self, *args, **kwargs)

    monkeypatch.setattr(httpx.AsyncClient, "__init__", spy_init)
    build_production_compiler(Settings(
        metrics_catalog_path=str(metrics),
        sectors_catalog_path=str(sectors),
        entity_catalog_path=str(entities),
    ))

    explicit_calls = [c for c in calls if "trust_env" in c]
    assert len(explicit_calls) == 2
    assert all(call["trust_env"] is False for call in explicit_calls)


def test_llm_client_uses_configured_timeout(monkeypatch, tmp_path: Path):
    metrics = tmp_path / "metrics.json"
    sectors = tmp_path / "sectors.json"
    entities = tmp_path / "entities.json"
    metrics.write_text("[]", encoding="utf-8")
    sectors.write_text("[]", encoding="utf-8")
    entities.write_text("[]", encoding="utf-8")
    import httpx
    calls = []
    orig_init = httpx.AsyncClient.__init__

    def spy_init(self, *args, **kwargs):
        calls.append(kwargs)
        orig_init(self, *args, **kwargs)

    monkeypatch.setattr(httpx.AsyncClient, "__init__", spy_init)
    build_production_compiler(Settings(
        metrics_catalog_path=str(metrics),
        sectors_catalog_path=str(sectors),
        entity_catalog_path=str(entities),
        llm_timeout_seconds=240,
    ))

    assert calls[0]["timeout"] == 240
