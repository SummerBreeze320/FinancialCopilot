from typing import Any
from app.models.dsl import (
    BusinessGroup,
    FundScreeningDsl,
    SectorExpression,
    SectorLeaf,
    SortItem,
)
from app.models.dsl_nodes import MetricLogicNode, MetricPredicate, MetricNode
from app.models.semantic_plan import AdjudicationResult, SemanticPlan

DEFAULT_SORT = SortItem(metric="fund_code", direction="ASC", nulls="LAST")
DEFAULT_LIMIT = 20


class CommandBuilder:
    def __init__(self, default_sector_id: str = "1000019220000000") -> None:
        self._default_sector_id = default_sector_id

    def build_from_plan(
        self,
        plan: SemanticPlan,
        adjudication: AdjudicationResult,
        contracts: dict[str, Any],
    ) -> FundScreeningDsl:
        """根据 SemanticPlan 逻辑树与 AdjudicationResult 叶子绑定确定性构建 AST/DSL。"""
        binding_map = {b.condition_id: b for b in adjudication.condition_bindings}

        def build_sector_expr(tree: Any) -> SectorExpression:
            children = []
            for item in getattr(tree, "children", []):
                cid = getattr(item, "condition_id", None) or getattr(item, "conditionId", None)
                if cid and cid in binding_map:
                    b = binding_map[cid]
                    sec_id = getattr(b, "sector_id", None) or getattr(b, "candidate_key", None)
                    if sec_id:
                        children.append(SectorLeaf(sectorId=sec_id))
                elif hasattr(item, "children"):
                    sub_sec = build_sector_expr(item)
                    if sub_sec.children:
                        children.append(sub_sec)
            if not children:
                children = [SectorLeaf(sectorId=self._default_sector_id)]
            return SectorExpression(operator=getattr(tree, "operator", "OR"), children=children)

        def build_metric_expr(tree: Any) -> MetricNode | None:
            cid = getattr(tree, "condition_id", None) or getattr(tree, "conditionId", None)
            if cid:
                if cid not in binding_map:
                    return None
                b = binding_map[cid]
                domain = getattr(b, "domain", None)
                c = contracts.get(b.candidate_key)
                if not c and hasattr(b, "selected_candidate_id"):
                    c = contracts.get(b.selected_candidate_id)

                params = []
                if isinstance(getattr(b, "params", None), dict):
                    params = [{"key": k, "value": v} for k, v in b.params.items()]
                elif isinstance(getattr(b, "params", None), list):
                    params = b.params

                metric_name = b.candidate_key
                val = getattr(b, "value", None)
                unit = getattr(b, "unit", None)
                operator = getattr(b, "operator", "=") or "="

                if not unit and c:
                    res_c = getattr(c, "result_contract", None) or (c.get("result_contract") if isinstance(c, dict) else None)
                    if isinstance(res_c, dict):
                        unit = res_c.get("unit")
                    elif hasattr(res_c, "unit"):
                        unit = res_c.unit

                # Entity binding dispatch
                is_entity = domain == "entity" or (c and getattr(c, "domain", None) == "entity")
                if is_entity:
                    pref = None
                    val_type = "ENTITY_REF"
                    if c:
                        pref = getattr(c, "preferred_metric", None) or getattr(c, "preferred_indicator", None)
                        val_type = getattr(c, "value_type", "ENTITY_REF") or "ENTITY_REF"
                    binding_role = getattr(b, "binding_role", None) or (c and getattr(c, "binding_role", None))

                    if pref:
                        metric_name = pref
                    elif binding_role in ("COMPANY", "FUND_COMPANY"):
                        metric_name = "f_info_fundcompany"
                    elif binding_role in ("MANAGER", "FUND_MANAGER"):
                        metric_name = "f_info_fundmanager"
                    elif binding_role in ("STOCK", "HOLDING_STOCK"):
                        metric_name = "f_info_stockholding"
                    elif binding_role in ("INDEX", "TRACKED_INDEX"):
                        metric_name = "f_info_trackedindex"
                    elif "company" in b.candidate_key:
                        metric_name = "f_info_fundcompany"
                    elif "manager" in b.candidate_key:
                        metric_name = "f_info_fundmanager"
                    elif "stock" in b.candidate_key:
                        metric_name = "f_info_stockholding"

                    val = val if val is not None else (c.key if c and getattr(c, "key", None) else b.candidate_key)
                    unit = unit or val_type
                    if not params and binding_role:
                        params = [{"role": binding_role}]

                return MetricPredicate(
                    operator=operator,
                    metric=metric_name,
                    value=val,
                    unit=unit,
                    params=params,
                )

            children = []
            for item in getattr(tree, "children", []):
                sub = build_metric_expr(item)
                if sub is not None:
                    children.append(sub)
            if not children:
                return None
            return MetricLogicNode(operator=getattr(tree, "operator", "AND"), children=children)

        default_sort = [SortItem(metric="fund_code", direction="ASC", nulls="LAST")]
        default_limit = 20

        groups = []
        for g in plan.groups:
            sec_expr = build_sector_expr(g.scope_tree)
            met_expr = build_metric_expr(g.metric_tree)
            if met_expr is None:
                # 默认全量匹配
                met_expr = MetricLogicNode(
                    operator="AND",
                    children=[
                        MetricPredicate(operator=">", metric="f_info_existingyear", value=0, unit="年", params=[])
                    ],
                )
            group_sorts = [
                SortItem(metric=s.candidate_key, direction=s.direction)
                for s in adjudication.sort_bindings
                if getattr(s, "level", "ROOT") == "GROUP" and getattr(s, "group_id", None) == g.group_id
            ]
            groups.append(
                BusinessGroup(
                    sector=sec_expr,
                    metric=met_expr,
                    sort=group_sorts or default_sort.copy(),
                    limit=getattr(g, "limit", None) or default_limit,
                )
            )

        if not groups:
            groups.append(
                BusinessGroup(
                    sector=SectorExpression(operator="OR", children=[SectorLeaf(sectorId=self._default_sector_id)]),
                    metric=MetricLogicNode(
                        operator="AND",
                        children=[MetricPredicate(operator=">", metric="f_info_existingyear", value=0, unit="年")],
                    ),
                    sort=default_sort.copy(),
                    limit=default_limit,
                )
            )

        root_sorts = [
            SortItem(metric=s.candidate_key, direction=s.direction)
            for s in adjudication.sort_bindings
            if getattr(s, "level", "ROOT") == "ROOT"
        ]
        return FundScreeningDsl(
            version="fund-screening/v1",
            operator=plan.group_operator,
            children=groups,
            sort=root_sorts or default_sort.copy(),
            limit=plan.limit or default_limit,
        )

    def build(self, decision: dict, candidate_pool) -> FundScreeningDsl:
        groups = []
        for group in decision["groups"]:
            explicit_sector_ids = group.get("sectorIds") or []
            for sector_id in explicit_sector_ids:
                contract = candidate_pool.contract(sector_id)
                if contract.domain != "scope":
                    raise ValueError(f"candidate {sector_id} is not a scope")
            sector_ids = explicit_sector_ids or [self._default_sector_id]
            predicates = []
            for item in group.get("metrics", ()):
                p = item.model_dump() if hasattr(item, "model_dump") else dict(item)
                raw_params = p.get("params", [])
                if isinstance(raw_params, dict):
                    param_list = [{"key": k, "value": v} for k, v in raw_params.items()]
                elif isinstance(raw_params, list):
                    param_list = [
                        item_p if isinstance(item_p, dict) and "key" in item_p else {"key": item_p["key"], "value": item_p["value"]}
                        for item_p in raw_params
                    ]
                else:
                    param_list = []
                pred_dict = {
                    "metric": p["metric"],
                    "operator": p["operator"],
                    "value": p["value"],
                    "unit": p.get("unit", "NUMBER"),
                    "params": param_list,
                }
                if p.get("period") is not None:
                    pred_dict["period"] = p["period"]
                predicates.append(pred_dict)

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
                    "params": [p.model_dump() if hasattr(p, "model_dump") else p for p in getattr(contract, "parameters", ())] if getattr(contract, "parameters", None) else ([{"role": contract.binding_role}] if getattr(contract, "binding_role", None) else []),
                })
            for predicate in group.get("metrics", ()):
                candidate_pool.contract(predicate["metric"])
            if not predicates:
                predicates = [{
                    "metric": "f_info_existingyear",
                    "operator": ">",
                    "value": 0,
                    "unit": "年",
                    "params": [],
                }]
            groups.append({
                "sector": {"operator": "OR", "children": [{"sectorId": key} for key in sector_ids]},
                "metric": {"operator": group.get("operator", "AND"), "children": predicates},
                "sort": group.get("sort") or [DEFAULT_SORT.model_dump()],
                "limit": group.get("limit") or DEFAULT_LIMIT,
            })
        return FundScreeningDsl.model_validate({
            "version": "fund-screening/v1",
            "operator": decision.get("operator", "AND"),
            "children": groups,
            "sort": decision.get("sort") or [DEFAULT_SORT.model_dump()],
            "limit": decision.get("limit") or DEFAULT_LIMIT,
        })
