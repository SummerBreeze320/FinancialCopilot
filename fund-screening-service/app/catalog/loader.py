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

        from app.catalog.parameter_rules import infer_aliases, infer_value_type
        from app.models.parameters import MetricParameterDefinition
        metrics = []
        for item in read(metrics_path):
            if not item.get("enabled", True):
                continue
            vt = item.get("value_type") or infer_value_type(item)
            aliases = tuple(item.get("aliases") or infer_aliases(item))
            params = tuple(MetricParameterDefinition.model_validate(p) for p in item.get("parameters", ()))
            metrics.append(CandidateContract(
                domain="metric",
                key=item["mnemonic"],
                name=item["index_name"],
                aliases=aliases,
                supported_operators=tuple(item.get("supported_operators") or (">", ">=", "<", "<=", "=")),
                value_type=vt,
                supported_usage=tuple(item.get("supported_usage", ("filter",))),
                parameters=params,
            ))
        scopes = [CandidateContract("scope", item["source_sector_id"], item["name"], tuple(item.get("aliases", ()))) for item in read(sectors_path) if item.get("enabled", True)]
        entity_source = read(entities_path)
        if isinstance(entity_source, dict):
            bindings = {
                "company_names": ("FUND_COMPANY", "f_info_mgrcomp"),
                "manager_names": ("FUND_MANAGER", "f_info_fundmanager"),
                "track_index_names": ("TRACK_INDEX", "f_info_trackindex"),
                "track_index_codes": ("TRACK_INDEX", "f_info_trackindex"),
                "stock_names": ("STOCK", "f_info_heavystock"),
                "stock_codes": ("STOCK", "f_info_heavystock"),
                "fund_names": ("FUND", "f_info_fundname"),
                "fund_codes": ("FUND", "f_info_fundcode"),
            }
            flattened = []
            for category, (role, metric) in bindings.items():
                for item in entity_source.get(category, ()):
                    flattened.append({
                        "key": item.get("id"),
                        "name": item.get("name") or item.get("code"),
                        "aliases": item.get("aliases", ()),
                        "binding_role": role,
                        "preferred_metric": metric,
                    })
            entity_source = flattened
        entities = [CandidateContract("entity", item["key"], item["name"], tuple(item.get("aliases", ())), binding_role=item.get("binding_role"), preferred_metric=item.get("preferred_metric"), supported_operators=("=", "!="), value_type="ENTITY_REF") for item in entity_source]
        return cls(metrics, scopes, entities)

    def metric_contract(self, key: str) -> CandidateContract:
        return self._metrics[key]
