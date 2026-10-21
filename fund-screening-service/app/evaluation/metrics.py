from __future__ import annotations

import math
from typing import Any


def compute_ranking_metrics(
    recalled_keys: list[str],
    relevant_keys: list[str],
    primary_key: str | None = None,
    k_list: tuple[int, ...] = (1, 3, 5, 10),
) -> dict[str, float]:
    """Calculates condition-level IR metrics (Precision@K, Recall@K, Hit@K, MRR, nDCG@K)."""
    relevant_set = set(relevant_keys)
    res: dict[str, float] = {}

    def get_rel(k: str) -> float:
        if primary_key and k == primary_key:
            return 2.0
        if k in relevant_set:
            return 1.0
        return 0.0

    # MRR
    first_hit_rank: int | None = None
    for idx, key in enumerate(recalled_keys):
        if key in relevant_set or (primary_key and key == primary_key):
            first_hit_rank = idx + 1
            break
    res["mrr"] = 1.0 / first_hit_rank if first_hit_rank else 0.0

    for k in k_list:
        top_k = recalled_keys[:k]
        hits = [key for key in top_k if key in relevant_set or (primary_key and key == primary_key)]

        # Hit@K
        res[f"hit@{k}"] = 1.0 if hits else 0.0

        # Precision@K
        res[f"precision@{k}"] = len(hits) / k if k > 0 else 0.0

        # Recall@K
        if not relevant_set and not primary_key:
            res[f"recall@{k}"] = 1.0
        else:
            all_target_keys = relevant_set | ({primary_key} if primary_key else set())
            unique_hits = {key for key in top_k if key in all_target_keys}
            res[f"recall@{k}"] = len(unique_hits) / len(all_target_keys)

        # nDCG@K
        dcg = sum((2 ** get_rel(key) - 1.0) / math.log2(i + 2) for i, key in enumerate(top_k) if get_rel(key) > 0)
        all_targets = list(relevant_set | ({primary_key} if primary_key else set()))
        ideal_rels = sorted([get_rel(key) for key in all_targets], reverse=True)[:k]
        idcg = sum((2 ** rel - 1.0) / math.log2(i + 2) for i, rel in enumerate(ideal_rels) if rel > 0)
        res[f"ndcg@{k}"] = (dcg / idcg) if idcg > 0 else 0.0

    return res


def _extract_dsl_elements(dsl_dict: dict[str, Any] | None) -> dict[str, Any]:
    if not dsl_dict or not isinstance(dsl_dict, dict):
        return {
            "operator": None,
            "metrics": set(),
            "sectors": set(),
            "limit": None,
            "sort": [],
            "params": {},
        }

    operator = dsl_dict.get("operator")
    limit = dsl_dict.get("limit")
    sort = dsl_dict.get("sort") or []
    metrics: set[str] = set()
    sectors: set[str] = set()
    params: dict[str, Any] = {}

    def walk_metric(node: Any) -> None:
        if isinstance(node, dict):
            if "metric" in node and isinstance(node["metric"], str):
                m_key = node["metric"]
                metrics.add(m_key)
                if "params" in node:
                    params[m_key] = node["params"]
                elif "operator" in node or "value" in node:
                    params[m_key] = {"operator": node.get("operator"), "value": node.get("value")}
            for k, v in node.items():
                if k in ("children", "metric"):
                    walk_metric(v)
        elif isinstance(node, list):
            for item in node:
                walk_metric(item)

    def walk_sector(node: Any) -> None:
        if isinstance(node, dict):
            sec_id = node.get("sectorId") or node.get("sector_id")
            if sec_id:
                sectors.add(str(sec_id))
            for k, v in node.items():
                if k in ("children", "sector"):
                    walk_sector(v)
        elif isinstance(node, list):
            for item in node:
                walk_sector(item)

    children = dsl_dict.get("children") or []
    for grp in children:
        if isinstance(grp, dict):
            if "sector" in grp:
                walk_sector(grp["sector"])
            if "metric" in grp:
                walk_metric(grp["metric"])
        else:
            walk_metric(grp)
            walk_sector(grp)

    # In case top-level or direct metric/sector exists
    if "metric" in dsl_dict:
        walk_metric(dsl_dict["metric"])
    if "sector" in dsl_dict:
        walk_sector(dsl_dict["sector"])

    return {
        "operator": operator,
        "metrics": metrics,
        "sectors": sectors,
        "limit": limit,
        "sort": sort,
        "params": params,
    }


def compute_dsl_match(
    actual_dsl: dict[str, Any] | None,
    expected_dsl: dict[str, Any] | None,
) -> dict[str, bool | float]:
    """Compares actual DSL against expected DSL on exact, structural, metric, sector, limit and sort levels."""
    if actual_dsl is None or expected_dsl is None:
        matched = actual_dsl == expected_dsl
        return {
            "exact_match": matched,
            "structural_match": matched,
            "metrics_match": matched,
            "sectors_match": matched,
            "params_match": matched,
            "sort_match": matched,
            "limit_match": matched,
            "overall_score": 1.0 if matched else 0.0,
        }

    exact_match = actual_dsl == expected_dsl
    actual_elem = _extract_dsl_elements(actual_dsl)
    expected_elem = _extract_dsl_elements(expected_dsl)

    metrics_match = actual_elem["metrics"] == expected_elem["metrics"]
    sectors_match = (
        actual_elem["sectors"] == expected_elem["sectors"]
        if expected_elem["sectors"]
        else True  # default sector fallback tolerated if not explicitly required
    )
    limit_match = actual_elem["limit"] == expected_elem["limit"] if expected_elem["limit"] is not None else True
    sort_match = actual_elem["sort"] == expected_elem["sort"] if expected_elem["sort"] else True
    operator_match = (
        actual_elem["operator"] == expected_elem["operator"]
        if expected_elem["operator"] is not None
        else True
    )

    structural_match = operator_match and metrics_match and sectors_match

    # Simple parameter match
    params_match = True
    for m_key, exp_p in expected_elem["params"].items():
        act_p = actual_elem["params"].get(m_key)
        if exp_p != act_p:
            params_match = False
            break

    scores = [
        1.0 if metrics_match else 0.0,
        1.0 if sectors_match else 0.0,
        1.0 if structural_match else 0.0,
        1.0 if limit_match else 0.0,
        1.0 if sort_match else 0.0,
    ]
    overall_score = sum(scores) / len(scores)

    return {
        "exact_match": exact_match,
        "structural_match": structural_match,
        "metrics_match": metrics_match,
        "sectors_match": sectors_match,
        "params_match": params_match,
        "sort_match": sort_match,
        "limit_match": limit_match,
        "overall_score": overall_score,
    }


def compute_clarification_match(
    actual_clar: dict[str, Any] | None,
    expected_clar: dict[str, Any] | None,
) -> dict[str, bool]:
    """Evaluates whether clarification code and required parameters match ground truth."""
    if actual_clar is None or expected_clar is None:
        matched = actual_clar == expected_clar
        return {"matched": matched, "code_matched": matched}

    code_matched = actual_clar.get("code") == expected_clar.get("code")
    param_matched = True
    if "parameter" in expected_clar and expected_clar["parameter"] is not None:
        param_matched = actual_clar.get("parameter") == expected_clar["parameter"]

    matched = code_matched and param_matched
    return {"matched": matched, "code_matched": code_matched}


def compute_target_accuracy(
    actual_dsl: dict[str, Any] | None,
    expected: Any,
) -> dict[str, float]:
    """计算候选最终选择准确率、Operator/Value/Unit 准确率、参数准确率以及 DSL 可执行率。"""
    res = {
        "candidate_selection_accuracy": 0.0,
        "operator_value_unit_accuracy": 0.0,
        "parameter_accuracy": 0.0,
        "executable_dsl_rate": 0.0,
    }
    if not actual_dsl or not isinstance(actual_dsl, dict):
        return res

    from app.pipeline.validator import validate_execution_gate
    is_valid, _ = validate_execution_gate(actual_dsl)
    res["executable_dsl_rate"] = 1.0 if is_valid else 0.0

    if not expected:
        res["candidate_selection_accuracy"] = 1.0 if is_valid else 0.0
        res["operator_value_unit_accuracy"] = 1.0 if is_valid else 0.0
        res["parameter_accuracy"] = 1.0 if is_valid else 0.0
        return res

    exp_dict = expected.model_dump(by_alias=True) if hasattr(expected, "model_dump") else expected
    target_metric = exp_dict.get("metric")
    target_sector = exp_dict.get("sector")
    target_op = exp_dict.get("operator")
    target_val = exp_dict.get("value")
    target_unit = exp_dict.get("unit")
    target_params = exp_dict.get("params") or {}

    matched_leaf = None
    matched_sector = False

    def find_in_node(node: Any):
        nonlocal matched_leaf, matched_sector
        if isinstance(node, dict):
            if target_metric and node.get("metric") == target_metric:
                matched_leaf = node
            sec_id = node.get("sectorId") or node.get("sector_id")
            if target_sector and str(sec_id) == str(target_sector):
                matched_sector = True
            for v in node.values():
                find_in_node(v)
        elif isinstance(node, list):
            for item in node:
                find_in_node(item)

    find_in_node(actual_dsl)

    if target_metric and matched_leaf:
        res["candidate_selection_accuracy"] = 1.0
    elif target_sector and matched_sector:
        res["candidate_selection_accuracy"] = 1.0

    if matched_leaf:
        op_match = True if target_op is None else (matched_leaf.get("operator") == target_op)
        val_match = True
        if target_val is not None:
            actual_val = matched_leaf.get("value")
            try:
                val_match = float(actual_val) == float(target_val)
            except (ValueError, TypeError):
                val_match = actual_val == target_val
        unit_match = True if target_unit is None else (matched_leaf.get("unit") == target_unit)

        if op_match and val_match and unit_match:
            res["operator_value_unit_accuracy"] = 1.0

        param_match = True
        if target_params:
            actual_params = {}
            for p in matched_leaf.get("params", []):
                if isinstance(p, dict) and "key" in p:
                    actual_params[p["key"]] = p.get("value")
            for pk, pv in target_params.items():
                if str(actual_params.get(pk)) != str(pv):
                    param_match = False
                    break
        res["parameter_accuracy"] = 1.0 if param_match else 0.0
    elif target_sector and matched_sector:
        res["operator_value_unit_accuracy"] = 1.0
        res["parameter_accuracy"] = 1.0

    return res
