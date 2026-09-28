from collections import Counter

from app.evaluation.models import ErrorReason


def classify_failure(case) -> ErrorReason | None:
    """把未命中归因为可直接执行的数据治理动作。"""
    if case.expected_key in case.recalled_keys:
        return None
    if case.expected_alias and case.expected_alias in case.query:
        return ErrorReason.ALIAS_MISSING
    if case.vector_keys is not None and case.expected_key not in case.vector_keys:
        return ErrorReason.VECTOR_RECALL_WEAK
    if case.vector_keys is not None and case.expected_key in case.vector_keys and case.reranked_keys is not None and case.expected_key not in case.reranked_keys:
        return ErrorReason.RERANK_ISSUE
    return ErrorReason.EMBEDDING_TEXT_INSUFFICIENT


def render_report(cases) -> dict:
    total = len(cases)
    top1 = sum(case.recalled_keys[:1] == [case.expected_key] for case in cases)
    top3 = sum(case.expected_key in case.recalled_keys[:3] for case in cases)
    reasons = [case.error_reason or (classify_failure(case).value if classify_failure(case) else None) for case in cases]
    backlog = {}
    for case, reason in zip(cases, reasons):
        if reason:
            backlog.setdefault(reason, []).append(case.expected_key)
    return {
        "top1_rate": top1 / total if total else 0,
        "top3_rate": top3 / total if total else 0,
        "error_reasons": dict(Counter(reason for reason in reasons if reason)),
        "change_backlog": backlog,
    }
