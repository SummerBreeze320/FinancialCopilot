import re
from enum import StrEnum
from typing import Any

from app.models.dsl import FundScreeningDsl
from app.models.edit_plan import EditPlan, EditOperation
from app.models.semantic_plan import (
    SemanticPlan,
    PlanGroup,
    MetricTree,
    ScopeTree,
    MetricLeaf,
    ScopeLeaf,
)


class EditKind(StrEnum):
    NEW = "new"
    SIMPLE = "simple"
    COMPLEX = "complex"


def classify_edit(query: str, previous_dsl: dict | None) -> EditKind:
    """仅在用户明确引用上一轮筛选时识别重建的复杂度。"""
    if previous_dsl is None:
        return EditKind.NEW

    normalized = query.strip()
    has_previous_reference = any(token in normalized for token in (
        "刚才", "之前", "上一轮", "上轮", "在此基础上", "这些条件", "那个", "刚刚",
    ))
    has_edit_action = any(token in normalized for token in (
        "新增", "增加", "加一个", "再加", "补充", "删除", "去掉", "移除", "不要",
        "修改", "改成", "改为", "替换", "换成", "保留", "排序", "升序", "降序",
        "重新筛选", "重新来", "清空",
    ))
    has_limit_or_sort = bool(re.search(r"(?:改成|改为|修改为|调整为|取)?\s*前\s*\d+\s*(?:只|支|条|个|项)?", normalized)) or any(token in normalized for token in ("升序", "降序", "limit", "排序"))
    has_explicit_edit = has_previous_reference or has_edit_action or has_limit_or_sort
    if not has_explicit_edit:
        return EditKind.NEW

    # Complex edit actions: adding new conditions, replacing, complex branching
    if any(token in normalized for token in (
        "新增", "增加", "加一个", "再加", "补充",
        "替换", "换成", "改成指标", "或者", "排除", "重新筛选", "重新来", "清空",
        "不要之前的", "不要前面", "筛选",
    )):
        return EditKind.COMPLEX

    # Simple condition removal: "去掉规模限制", "删除成立年限条件", "不要最大回撤"
    if any(token in normalized for token in ("删除", "去掉", "移除", "不要")):
        return EditKind.SIMPLE

    # Simple limit / sort modification
    if any(token in normalized for token in ("limit", "排序", "升序", "降序")) or re.search(
        r"(?:改成|改为|修改为|调整为|取)?\s*前\s*\d+\s*(?:只|支|条|个|项)?", normalized
    ):
        return EditKind.SIMPLE

    return EditKind.COMPLEX


_METRIC_ALIAS_MAP = {
    "规模": "fundscale",
    "基金规模": "fundscale",
    "成立年限": "existingyear",
    "年限": "existingyear",
    "成立时间": "founddate",
    "成立": "existingyear",
    "收益": "return",
    "收益率": "return",
    "回报": "return",
    "回撤": "downside",
    "最大回撤": "downside",
    "夏普": "sharpe",
    "夏普率": "sharpe",
    "夏普比率": "sharpe",
    "跟踪指数": "trackindex",
    "指数": "trackindex",
    "机构持有比例": "institutionholdingratio",
    "机构占比": "institutionholdingratio",
}


def _metric_matches_token(metric_name: str, token: str) -> bool:
    if not metric_name:
        return False
    lower_metric = metric_name.lower()
    if token.lower() in lower_metric:
        return True
    keyword = _METRIC_ALIAS_MAP.get(token)
    if keyword and keyword in lower_metric:
        return True
    return False


def _remove_metric_from_expr(expr, token: str):
    if expr is None:
        return None
    metric_name = getattr(expr, "metric", None)
    if metric_name:
        if _metric_matches_token(metric_name, token):
            return None
        return expr
    children = getattr(expr, "children", None)
    if children:
        new_children = []
        for child in children:
            res = _remove_metric_from_expr(child, token)
            if res is not None:
                new_children.append(res)
        if not new_children:
            return None
        return expr.model_copy(update={"children": new_children})
    return expr


def apply_simple_edit(previous_dsl: FundScreeningDsl, query: str) -> FundScreeningDsl:
    result = previous_dsl

    # 1. Update limit
    limit_match = re.search(r"(?:前|limit)\s*(\d+)\s*(?:只|支|条|个|项)?", query, re.I)
    if limit_match:
        new_limit = int(limit_match.group(1))
        group_match = re.search(r"第\s*(\d+|[一二三四五六七八九十])\s*组", query)
        target_group_idx = None
        if group_match:
            g_str = group_match.group(1)
            num_map = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}
            target_group_idx = (num_map.get(g_str) or int(g_str)) - 1

        if target_group_idx is not None and 0 <= target_group_idx < len(result.children):
            # Only update the specified group limit, root limit is preserved
            updated_groups = [
                g.model_copy(update={"limit": new_limit}) if i == target_group_idx else g
                for i, g in enumerate(result.children)
            ]
            result = result.model_copy(update={"children": updated_groups})
        elif len(result.children) == 1:
            # Single group: root and single group stay consistent
            result = result.model_copy(update={"limit": new_limit})
            updated_groups = [result.children[0].model_copy(update={"limit": new_limit})]
            result = result.model_copy(update={"children": updated_groups})
        else:
            # Multi-group, no group specified: update root limit only
            result = result.model_copy(update={"limit": new_limit})

    # 2. Update sort direction
    if "升序" in query:
        if result.sort:
            result = result.model_copy(update={"sort": [item.model_copy(update={"direction": "ASC"}) for item in result.sort]})
        updated_groups = []
        for g in result.children:
            if g.sort:
                updated_groups.append(g.model_copy(update={"sort": [item.model_copy(update={"direction": "ASC"}) for item in g.sort]}))
            else:
                updated_groups.append(g)
        result = result.model_copy(update={"children": updated_groups})
    elif "降序" in query:
        if result.sort:
            result = result.model_copy(update={"sort": [item.model_copy(update={"direction": "DESC"}) for item in result.sort]})
        updated_groups = []
        for g in result.children:
            if g.sort:
                updated_groups.append(g.model_copy(update={"sort": [item.model_copy(update={"direction": "DESC"}) for item in g.sort]}))
            else:
                updated_groups.append(g)
        result = result.model_copy(update={"children": updated_groups})

    # 3. Remove condition if requested
    rem_match = re.search(r"(?:去掉|删除|移除|不要)\s*([^\s,，。；;]+?)(?:限制|条件|要求)?(?:[,，。；;]|$)", query)
    if rem_match:
        target_token = rem_match.group(1).strip()
        updated_groups = []
        for g in result.children:
            updated_metric = _remove_metric_from_expr(g.metric, target_token)
            updated_groups.append(g.model_copy(update={"metric": updated_metric}))
        result = result.model_copy(update={"children": updated_groups})

    return result


def _matches_cid(node: Any, target_cid: str) -> bool:
    return getattr(node, "condition_id", None) == target_cid or getattr(node, "conditionId", None) == target_cid


def _gather_leaves(tree: Any) -> list[Any]:
    if tree is None:
        return []
    leaves = []
    for child in getattr(tree, "children", []):
        if hasattr(child, "mention") and (hasattr(child, "condition_id") or hasattr(child, "conditionId")):
            leaves.append(child)
        elif hasattr(child, "children"):
            leaves.extend(_gather_leaves(child))
    return leaves


def summarize_plan_for_edit(plan: SemanticPlan) -> dict[str, Any]:
    conditions = []
    for g in plan.groups:
        for c in _gather_leaves(g.scope_tree):
            cid = getattr(c, "condition_id", None) or getattr(c, "conditionId", None)
            conditions.append({
                "conditionId": cid,
                "domain": "scope",
                "mention": c.mention,
                "groupId": g.group_id,
            })
        for c in _gather_leaves(g.metric_tree):
            cid = getattr(c, "condition_id", None) or getattr(c, "conditionId", None)
            conditions.append({
                "conditionId": cid,
                "domain": getattr(c, "domain", "metric"),
                "mention": c.mention,
                "operatorHint": getattr(c, "operator_hint", None),
                "valueHint": getattr(c, "value_hint", None),
                "unitHint": getattr(c, "unit_hint", None),
                "groupId": g.group_id,
            })
    return {
        "groups": len(plan.groups),
        "conditions": conditions,
        "sortMentions": [s.model_dump(by_alias=True) for s in plan.sort_mentions],
        "limit": plan.limit,
    }


def _replace_in_tree(tree: Any, target_cid: str, replacement: Any) -> Any:
    if tree is None:
        return None
    new_children = []
    for child in getattr(tree, "children", []):
        if _matches_cid(child, target_cid):
            new_children.append(replacement)
        elif hasattr(child, "children"):
            new_children.append(_replace_in_tree(child, target_cid, replacement))
        else:
            new_children.append(child)
    return tree.model_copy(update={"children": new_children})


def _remove_from_tree(tree: Any, target_cid: str) -> Any:
    if tree is None:
        return None
    new_children = []
    for child in getattr(tree, "children", []):
        if _matches_cid(child, target_cid):
            continue
        elif hasattr(child, "children"):
            sub = _remove_from_tree(child, target_cid)
            if getattr(sub, "children", []):
                new_children.append(sub)
        else:
            new_children.append(child)
    return tree.model_copy(update={"children": new_children})


def apply_edit_plan(previous_plan: SemanticPlan, edit_plan: EditPlan) -> tuple[SemanticPlan, list[str]]:
    recall_ids: list[str] = []

    # Check for RESET
    if any(op.action == "RESET" for op in edit_plan.operations):
        new_plan = SemanticPlan(
            intent="EDIT",
            groups=[],
            sort_mentions=edit_plan.sort_mentions or [],
            limit=edit_plan.limit or 20,
        )
        return new_plan, recall_ids

    # Deep copy groups
    new_groups = [g.model_copy(deep=True) for g in previous_plan.groups]
    if not new_groups:
        new_groups = [PlanGroup(groupId="g1", scopeTree=ScopeTree(), metricTree=MetricTree())]

    for op in edit_plan.operations:
        if op.action == "ADD" and op.condition is not None:
            cid = getattr(op.condition, "condition_id", None) or getattr(op.condition, "conditionId", None)
            if cid:
                recall_ids.append(cid)
            target_gid = op.group_id or new_groups[0].group_id
            target_g = next((g for g in new_groups if g.group_id == target_gid), new_groups[0])
            if isinstance(op.condition, ScopeLeaf):
                target_g.scope_tree.children.append(op.condition)
            else:
                target_g.metric_tree.children.append(op.condition)

        elif op.action == "REPLACE" and op.condition is not None and op.target_condition_id:
            cid = getattr(op.condition, "condition_id", None) or getattr(op.condition, "conditionId", None)
            if cid:
                recall_ids.append(cid)
            target_cid = op.target_condition_id
            for g in new_groups:
                g.scope_tree = _replace_in_tree(g.scope_tree, target_cid, op.condition)
                g.metric_tree = _replace_in_tree(g.metric_tree, target_cid, op.condition)

        elif op.action == "REMOVE" and op.target_condition_id:
            target_cid = op.target_condition_id
            for g in new_groups:
                g.scope_tree = _remove_from_tree(g.scope_tree, target_cid)
                g.metric_tree = _remove_from_tree(g.metric_tree, target_cid)

        elif op.action == "CHANGE_OPERATOR" and op.operator:
            if op.group_id:
                for g in new_groups:
                    if g.group_id == op.group_id:
                        g.metric_tree.operator = op.operator
            else:
                for g in new_groups:
                    g.metric_tree.operator = op.operator

    new_sort = edit_plan.sort_mentions if edit_plan.sort_mentions is not None else previous_plan.sort_mentions
    new_limit = edit_plan.limit if edit_plan.limit is not None else previous_plan.limit

    return SemanticPlan(
        intent="EDIT",
        group_operator=previous_plan.group_operator,
        groups=new_groups,
        sort_mentions=new_sort,
        limit=new_limit,
    ), recall_ids

