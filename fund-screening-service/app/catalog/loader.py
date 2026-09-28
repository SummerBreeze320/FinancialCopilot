import json
from pathlib import Path

from app.models.candidate import CandidateContract


class Catalog:
    def __init__(self, metrics: list[CandidateContract], scopes: list[CandidateContract], entities: list[CandidateContract]) -> None:
        self.metrics, self.scopes, self.entities = metrics, scopes, entities
        self._metrics = {item.key: item for item in metrics}

    @classmethod
    def from_files(cls, metrics_path: Path, sectors_path: Path, entities_path: Path) -> "Catalog":
        def read(path: Path) -> list[dict]:
            return json.loads(path.read_text(encoding="utf-8"))

        metrics = [CandidateContract("metric", item["mnemonic"], item["index_name"], tuple(item.get("aliases", ())), supported_usage=tuple(item.get("supported_usage", ("filter",)))) for item in read(metrics_path) if item.get("enabled", True)]
        scopes = [CandidateContract("scope", item["source_sector_id"], item["name"], tuple(item.get("aliases", ()))) for item in read(sectors_path) if item.get("enabled", True)]
        entities = [CandidateContract("entity", item["key"], item["name"], tuple(item.get("aliases", ())), binding_role=item.get("binding_role"), preferred_metric=item.get("preferred_metric"), value_type="ENTITY_REF") for item in read(entities_path)]
        return cls(metrics, scopes, entities)

    def metric_contract(self, key: str) -> CandidateContract:
        return self._metrics[key]
