from app.models.dsl import FundScreeningDsl


class CommandBuilder:
    def __init__(self, default_sector_id: str = "1000019220000000") -> None:
        self._default_sector_id = default_sector_id

    def build(self, decision: dict, candidate_pool) -> FundScreeningDsl:
        groups = []
        for group in decision["groups"]:
            explicit_sector_ids = group.get("sectorIds") or []
            for sector_id in explicit_sector_ids:
                contract = candidate_pool.contract(sector_id)
                if contract.domain != "scope":
                    raise ValueError(f"candidate {sector_id} is not a scope")
            sector_ids = explicit_sector_ids or [self._default_sector_id]
            predicates = list(group.get("metrics", ()))
            for entity_ref in group.get("entities", ()):
                contract = candidate_pool.contract(entity_ref["entity"])
                if contract.domain != "entity" or not contract.preferred_metric:
                    raise ValueError(f"entity {contract.key} has no bindable contract")
                operator = entity_ref.get("operator", "=")
                if operator not in contract.supported_operators:
                    raise ValueError(f"operator {operator} is not supported by {contract.key}")
                predicates.append({
                    "metric": contract.preferred_metric,
                    "operator": operator,
                    "value": contract.key,
                    "unit": contract.value_type,
                })
            for predicate in group.get("metrics", ()):
                candidate_pool.contract(predicate["metric"])
            groups.append({
                "sector": {"operator": "OR", "children": [{"sectorId": key, "match": "SELF_OR_DESCENDANT"} for key in sector_ids]},
                "metric": {"operator": group.get("operator", "AND"), "children": predicates},
                "sort": group.get("sort", []), "limit": group.get("limit"),
            })
        return FundScreeningDsl.model_validate({"version": "fund-screening/v1", "operator": decision.get("operator", "AND"), "children": groups, "sort": decision.get("sort", []), "limit": decision.get("limit", 20)})
