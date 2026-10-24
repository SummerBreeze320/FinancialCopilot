from __future__ import annotations

import re
from typing import Literal

from app.graph.state import FundScreeningState


def classify_screening_route(state: FundScreeningState) -> Literal["direct", "simple", "full"]:
    query = state.get("normalized_query") or state.get("query", "")

    # 1. 复杂逻辑判定 (OR / NOT / 括号嵌套) -> full
    if any(k in query for k in ("或者", "或", "OR", "排除", "不是", "NOT", "且（", "且(")):
        return "full"

    # 2. 多业务组分隔符判定 -> full
    if len(re.split(r"[;；]", query)) > 1:
        return "full"

    clean_q = query.strip()
    # 3. 纯基金代码判定 (如 001938, 001938.OF, 161725) -> direct
    if re.fullmatch(r"\d{6}(\.[A-Za-z]+)?", clean_q):
        return "direct"

    # 4. 候选池有精确匹配实体且 query 无过滤操作符 -> direct
    has_operator = any(op in clean_q for op in (">", "<", "=", "大于", "小于", "超过", "低于", "高于"))
    if not has_operator:
        pool = state.get("candidate_pool") or []
        for c in pool:
            if c.get("domain") == "entity" and c.get("score", 0.0) >= 0.95:
                name = c.get("name", "")
                aliases = c.get("aliases", [])
                if clean_q == name or clean_q in aliases or not name:
                    return "direct"

    # 5. 简单单组且少于 4 个 AND 条件 -> simple
    conditions = [c for c in re.split(r"[且与和、,，\s]+", query) if c.strip()]
    if len(conditions) <= 3:
        return "simple"

    return "full"


def classify_plan_route(plan: Any, by_condition: dict[str, Any]) -> tuple[Literal["direct", "simple", "full"], str]:
    """根据 Semantic Plan 的结构复杂度与候选歧义度，确定性分流至 direct、simple 或 full。"""
    groups = getattr(plan, "groups", [])
    if len(groups) > 1:
        return "full", "MULTI_GROUP_DETECTED"

    group_op = getattr(plan, "group_operator", "AND")
    if group_op != "AND":
        return "full", "MULTI_GROUP_OR"

    if not groups:
        return "simple", "EMPTY_PLAN_DEFAULT_SIMPLE"

    group = groups[0]

    def has_not_operator(tree: Any) -> bool:
        if not tree:
            return False
        if getattr(tree, "operator", None) == "NOT":
            return True
        for child in getattr(tree, "children", []):
            if hasattr(child, "children") and has_not_operator(child):
                return True
        return False

    def has_nested_or(tree: Any, is_root: bool = True) -> bool:
        if not tree:
            return False
        op = getattr(tree, "operator", None)
        if not is_root and op == "OR":
            return True
        for child in getattr(tree, "children", []):
            if hasattr(child, "children") and has_nested_or(child, is_root=False):
                return True
        return False

    metric_tree = getattr(group, "metric_tree", None)
    scope_tree = getattr(group, "scope_tree", None)
    if has_not_operator(metric_tree) or has_not_operator(scope_tree):
        return "full", "NOT_OPERATOR_DETECTED"
    if has_nested_or(metric_tree, is_root=True) or has_nested_or(scope_tree, is_root=True):
        return "full", "NESTED_OR_DETECTED"

    def count_leaves(tree: Any) -> int:
        count = 0
        for child in getattr(tree, "children", []):
            if hasattr(child, "domain"):
                count += 1
            elif hasattr(child, "children"):
                count += count_leaves(child)
        return count

    total_conditions = count_leaves(scope_tree) + count_leaves(metric_tree)
    if total_conditions > 3:
        return "full", "CONDITION_COUNT_EXCEEDED"

    def get_max_depth(tree: Any) -> int:
        if not hasattr(tree, "children") or not tree.children:
            return 1
        sub_depths = [get_max_depth(c) for c in tree.children if hasattr(c, "children")]
        return 1 + (max(sub_depths) if sub_depths else 0)

    depth = max(
        get_max_depth(scope_tree),
        get_max_depth(metric_tree),
    )
    if depth > 2:
        return "full", "TREE_DEPTH_EXCEEDED"

    # 检查候选歧义度 (Top1 vs Top2 分数差 < 0.15 且都较高)
    all_exact = bool(by_condition)
    for cid, cond_info in by_condition.items():
        if isinstance(cond_info, list):
            candidates = cond_info
        elif isinstance(cond_info, dict):
            candidates = cond_info.get("candidates", [])
        else:
            candidates = getattr(cond_info, "candidates", [])

        if not candidates:
            all_exact = False
            continue

        def get_score(c: Any) -> float:
            if hasattr(c, "retrieval_score"):
                return c.retrieval_score
            if hasattr(c, "score"):
                return c.score
            if isinstance(c, dict):
                return c.get("retrieval_score") or c.get("score") or 0.0
            return 0.0

        def get_channel(c: Any) -> str:
            if hasattr(c, "retrieval_channel"):
                return c.retrieval_channel
            if isinstance(c, dict):
                return c.get("retrieval_channel", "")
            return ""

        s1 = get_score(candidates[0])
        ch1 = get_channel(candidates[0])

        if len(candidates) >= 2:
            s2 = get_score(candidates[1])
            if s1 >= 0.7 and s2 >= 0.7 and abs(s1 - s2) < 0.15:
                return "full", "HIGH_CANDIDATE_AMBIGUITY"
            if s1 < 0.98 or (s1 - s2 < 0.25):
                all_exact = False
        else:
            if s1 < 0.98 and ch1 != "direct":
                all_exact = False

    if all_exact:
        return "simple", "DETERMINISTIC_EXACT_MATCH"

    return "simple", "SINGLE_GROUP_SHALLOW_AND"
