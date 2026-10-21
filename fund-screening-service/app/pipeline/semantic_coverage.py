from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from app.models.semantic_plan import SemanticPlan, AdjudicationResult


@dataclass
class ComparisonFragment:
    text: str
    number_str: str
    number_val: float
    keyword: str | None = None


@dataclass
class CoverageResult:
    is_valid: bool
    missing_fragments: list[str] = field(default_factory=list)
    has_zero_placeholder: bool = False
    unbound_condition_ids: list[str] = field(default_factory=list)
    details: dict[str, Any] = field(default_factory=dict)


_COMPARISON_REGEX = re.compile(
    r"((?:规模|成立|年限|收益|回报|回撤|最大回撤|夏普|波动率|机构占比|持有比例)?[^，,。\s]*?"
    r"(?:>=|<=|>|<|=|大于等于|小于等于|大于|高于|超过|小于|低于|不超过|以内|以下)\s*"
    r"(\d+(?:\.\d+)?)\s*(?:%|亿|万|年|只|支)?|"
    r"(?:规模|成立|年限|收益|回报|回撤|最大回撤|夏普|波动率)?[^，,。\s]*?"
    r"(\d+(?:\.\d+)?)\s*(?:%|亿|万|年)\s*(?:以上|以下|以内|超|超过))"
)


def extract_query_comparisons(query: str) -> list[ComparisonFragment]:
    """提取 query 中明确出现的数值比较片段。严禁猜指标 ID，只做文本与数值提取。"""
    fragments: list[ComparisonFragment] = []
    seen_texts = set()

    for m in _COMPARISON_REGEX.finditer(query):
        full_match = m.group(1).strip()
        num_str = m.group(2) or m.group(3)
        if not num_str or full_match in seen_texts:
            continue
        seen_texts.add(full_match)
        val = float(num_str)

        # 尝试提取伴随的业务词
        kw = None
        for cand_kw in ("规模", "成立", "年限", "收益", "回报", "回撤", "夏普", "波动率"):
            if cand_kw in full_match:
                kw = cand_kw
                break

        fragments.append(ComparisonFragment(text=full_match, number_str=num_str, number_val=val, keyword=kw))

    return fragments


def check_semantic_coverage(query: str, plan: SemanticPlan) -> CoverageResult:
    """检查 SemanticPlan 是否覆盖了 query 中的所有显式数值比较条件，且未填入无由占位符 0。"""
    fragments = extract_query_comparisons(query)
    
    metric_leaves = []
    scope_leaves = []
    for g in plan.groups:
        if g.metric_tree and g.metric_tree.children:
            metric_leaves.extend(g.metric_tree.children)
        if g.scope_tree and g.scope_tree.children:
            scope_leaves.extend(g.scope_tree.children)

    all_leaves = metric_leaves + scope_leaves

    # 1. 检查无根据填 0 占位符
    has_zero_in_query = bool(re.search(r"(?:^|[^\d])0(?!\d)|零", query))
    has_zero_placeholder = False
    for leaf in metric_leaves:
        val = getattr(leaf, "value_hint", None)
        mention = getattr(leaf, "mention", "") or ""
        # 排除 query 明确指明 0 的情况
        if not has_zero_in_query:
            if val is not None and val == 0:
                has_zero_placeholder = True
                break
            if re.search(r"(?:>|>=|<|<=|=|大于|小于)\s*0(?!\d)", mention):
                has_zero_placeholder = True
                break

    # 2. 检查数值片段覆盖
    missing_fragments: list[str] = []
    for frag in fragments:
        # 在 leaves 中寻找匹配：数值相符或文本包含
        matched = False
        for leaf in all_leaves:
            mention = getattr(leaf, "mention", "") or ""
            val = getattr(leaf, "value_hint", None)
            
            val_matched = (val is not None and abs(val - frag.number_val) < 1e-4)
            text_matched = (frag.number_str in mention)
            
            if val_matched or text_matched:
                if frag.keyword:
                    # 如果片段有明确业务词（如规模），leaf 需包含该词或相似词
                    if frag.keyword in mention or (frag.keyword in ("收益", "回报") and any(w in mention for w in ("收益", "回报"))):
                        matched = True
                        break
                    elif not any(k in mention for k in ("规模", "成立", "回撤", "收益", "夏普")):
                        # 如果 leaf 没写别的业务词，也算匹配
                        matched = True
                        break
                else:
                    matched = True
                    break

        if not matched:
            missing_fragments.append(frag.text)

    is_valid = (len(missing_fragments) == 0) and (not has_zero_placeholder)
    return CoverageResult(
        is_valid=is_valid,
        missing_fragments=missing_fragments,
        has_zero_placeholder=has_zero_placeholder,
        details={"total_fragments": len(fragments), "leaf_count": len(all_leaves)},
    )


def check_binding_coverage(plan: SemanticPlan, adjudication: AdjudicationResult) -> CoverageResult:
    """检查 AdjudicationResult 是否完成了对 SemanticPlan 所有 conditionId 的绑定。"""
    plan_condition_ids = set()
    for g in plan.groups:
        if g.metric_tree and g.metric_tree.children:
            for m in g.metric_tree.children:
                if m.condition_id:
                    plan_condition_ids.add(m.condition_id)
        if g.scope_tree and g.scope_tree.children:
            for s in g.scope_tree.children:
                if s.condition_id:
                    plan_condition_ids.add(s.condition_id)

    bound_ids = {b.condition_id for b in adjudication.condition_bindings if b.condition_id}
    clarified_ids = {c.condition_id for c in adjudication.clarifications if c.condition_id}

    unbound = sorted(list(plan_condition_ids - bound_ids - clarified_ids))
    return CoverageResult(
        is_valid=(len(unbound) == 0),
        unbound_condition_ids=unbound,
        details={"total_plan_conditions": len(plan_condition_ids), "bound": len(bound_ids)},
    )
