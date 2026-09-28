from app.models.dsl import FundScreeningDsl


class CommandBuilder:
    def __init__(self, default_sector_id: str = "1000019220000000") -> None:
        self._default_sector_id = default_sector_id

    def build(self, decision: dict, candidate_pool) -> FundScreeningDsl:
        groups = []
        for group in decision["groups"]:
            sector_ids = group.get("sectorIds") or [self._default_sector_id]
            for predicate in group["metrics"]:
                candidate_pool.contract(predicate["metric"])
            groups.append({
                "sector": {"operator": "OR", "children": [{"sectorId": key, "match": "SELF_OR_DESCENDANT"} for key in sector_ids]},
                "metric": {"operator": group.get("operator", "AND"), "children": group["metrics"]},
                "sort": group.get("sort", []), "limit": group.get("limit"),
            })
        return FundScreeningDsl.model_validate({"version": "fund-screening/v1", "operator": decision.get("operator", "AND"), "children": groups, "sort": decision.get("sort", []), "limit": decision.get("limit", 20)})
