from __future__ import annotations

from enum import Enum
from typing import Any
from app.evaluation.schema import GoldenCase


class ActionableReason(str, Enum):
    ALIAS_MISSING = "alias_missing"
    EMBEDDING_TEXT_INSUFFICIENT = "embedding_text_insufficient"
    VECTOR_RECALL_WEAK = "vector_recall_weak"
    RERANK_ISSUE = "rerank_issue"
    PLAN_ERROR = "plan_error"
    ADJUDICATION_ERROR = "adjudication_error"
    VALIDATOR_ERROR = "validator_error"


def classify_detailed_failure(
    case: GoldenCase,
    channel_recalls: dict[str, list[str]],
    e2e_result: dict[str, Any],
) -> ActionableReason:
    """Classifies a failure into one of 7 actionable root cause categories."""
    # 1. Validator error
    if e2e_result.get("validator_error") or e2e_result.get("error_type") == "validator":
        return ActionableReason.VALIDATOR_ERROR

    # Extract all relevant target keys
    all_targets: set[str] = set()
    for c in case.expected_conditions:
        all_targets.update(c.relevant_candidate_keys)
        if c.primary_candidate_key:
            all_targets.add(c.primary_candidate_key)

    merged_keys = set(channel_recalls.get("merged", []))
    vector_keys = set(channel_recalls.get("pgvector", []))
    lexical_keys = set(channel_recalls.get("lexical", []))

    # Recall failures: neither channel recalled the target
    if all_targets and not (all_targets & vector_keys) and not (all_targets & lexical_keys) and not (all_targets & merged_keys):
        if not channel_recalls.get("lexical") and not channel_recalls.get("pgvector"):
            return ActionableReason.ALIAS_MISSING
        return ActionableReason.VECTOR_RECALL_WEAK

    # 2. Plan error
    if case.expected_conditions and "plan" in e2e_result:
        plan = e2e_result["plan"]
        if not plan:
            return ActionableReason.PLAN_ERROR
        conditions = plan.get("conditions") if isinstance(plan, dict) else getattr(plan, "conditions", None)
        if conditions is not None and len(conditions) < len(case.expected_conditions):
            return ActionableReason.PLAN_ERROR

    # 3. Adjudication error: merged pool had candidates, but final binding missed them
    if all_targets and (all_targets & merged_keys):
        adj = e2e_result.get("adjudication")
        bound_keys: set[str] = set()
        if adj:
            bindings = adj.get("condition_bindings") if isinstance(adj, dict) else getattr(adj, "condition_bindings", [])
            for b in bindings:
                k = b.get("candidate_key") if isinstance(b, dict) else getattr(b, "candidate_key", None)
                if k:
                    bound_keys.add(k)
        if not (bound_keys & all_targets):
            return ActionableReason.ADJUDICATION_ERROR

    # 4. Rerank / Fusion issue: single channel retrieved it, but merged pool lost it
    vector_keys = set(channel_recalls.get("pgvector", []))
    lexical_keys = set(channel_recalls.get("lexical", []))
    if (all_targets & vector_keys) or (all_targets & lexical_keys):
        if not (all_targets & merged_keys):
            return ActionableReason.RERANK_ISSUE

    # 5. Recall failures: neither channel recalled the target
    if case.category in ("metric", "scope", "entity") or not channel_recalls.get("pgvector"):
        if not channel_recalls.get("lexical") and not channel_recalls.get("pgvector"):
            return ActionableReason.ALIAS_MISSING
        return ActionableReason.VECTOR_RECALL_WEAK

    if len(case.query) > 20:
        return ActionableReason.EMBEDDING_TEXT_INSUFFICIENT

    return ActionableReason.VECTOR_RECALL_WEAK


def get_remediation_suggestion(reason: str | ActionableReason, case: GoldenCase) -> str:
    """Returns specific, actionable engineering backlog recommendations."""
    r = ActionableReason(reason) if isinstance(reason, str) else reason
    if r == ActionableReason.ALIAS_MISSING:
        return f"在指标/板块/实体字典中为候选对象补充 aliases 别名映射 (query: '{case.query}')"
    elif r == ActionableReason.EMBEDDING_TEXT_INSUFFICIENT:
        return f"扩充候选对象 embedding 文档文本，增强投研场景语义关键词与业务描述"
    elif r == ActionableReason.VECTOR_RECALL_WEAK:
        return f"优化 pgvector 相似度阈值或增加 Top-K 检索深度，调优向量模型表示"
    elif r == ActionableReason.RERANK_ISSUE:
        return f"优化多通道融合 (RRF 排名融合 / 权重重排)，避免高相关候选在合并池被淘汰"
    elif r == ActionableReason.PLAN_ERROR:
        return f"优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例"
    elif r == ActionableReason.ADJUDICATION_ERROR:
        return f"调优 Adjudication 裁决模型 Prompt 与参数绑定逻辑，提高目标候选判定准确率"
    elif r == ActionableReason.VALIDATOR_ERROR:
        return f"检查并修正 AST 语法树生成逻辑或调整语义验证器校验边界"
    return "持续跟踪与评估"
