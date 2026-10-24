from __future__ import annotations

from typing import Any, Sequence
from app.models.candidate import CandidateContract


KNOWN_QUALIFIERS = (
    "场内", "场外", "非货币", "货币", "股票型", "债券型", "混合型",
    "指数型", "被动指数", "增强指数", "QDII", "FOF", "LOF", "ETF",
    "开放式", "封闭式", "合并", "非合并", "联接", "发起式", "普通",
    "偏股", "偏债", "平衡", "灵活配置", "中长期纯债", "短期纯债", "一级债", "二级债",
)

STATUS_RANK = {
    "APPROVED": 3,
    "STANDARD": 2,
    "INFERRED": 1,
}


def _is_exact_name(mention: str, candidate: CandidateContract) -> int:
    m = mention.strip().lower()
    c = candidate.name.strip().lower()
    return 1 if m == c else 0


def _is_exact_alias(mention: str, candidate: CandidateContract) -> int:
    m = mention.strip().lower()
    return 1 if any(m == a.strip().lower() for a in candidate.aliases) else 0


def _qualifier_coverage(mention: str, candidate: CandidateContract) -> float:
    m_lower = mention.lower()
    matched_in_mention = [q for q in KNOWN_QUALIFIERS if q.lower() in m_lower]
    candidate_text = (candidate.name + " " + " ".join(candidate.aliases)).lower()

    if matched_in_mention:
        covered = sum(1 for q in matched_in_mention if q.lower() in candidate_text)
        return covered / len(matched_in_mention)

    # Fallback to character overlap
    m_clean = set(m_lower.replace(" ", ""))
    c_clean = set(candidate_text.replace(" ", ""))
    if not m_clean:
        return 0.0
    return len(m_clean & c_clean) / len(m_clean)


def _is_param_satisfiable(candidate: CandidateContract) -> int:
    if not candidate.parameters:
        return 1
    for p in candidate.parameters:
        is_req = getattr(p, "required", False) if hasattr(p, "required") else (
            p.get("required", False) if isinstance(p, dict) else False
        )
        default_val = getattr(p, "default_value", None) if hasattr(p, "default_value") else (
            p.get("defaultValue") or p.get("default_value") if isinstance(p, dict) else None
        )
        if is_req and default_val is None:
            return 0
    return 1


def rank_candidates(mention: str, candidates: Sequence[CandidateContract]) -> list[CandidateContract]:
    """依据完整名称、精确别名、业务限定覆盖率、合同状态、参数可满足性、默认优先级与得分对候选进行确定性重排。"""
    if not candidates:
        return []

    # 相同 (domain, key) 去重并保留最高分来源
    dedup: dict[tuple[str, str], CandidateContract] = {}
    for c in candidates:
        pair = (c.domain, c.key)
        if pair not in dedup or (c.score > dedup[pair].score):
            dedup[pair] = c

    unique_candidates = list(dedup.values())

    def sort_key(c: CandidateContract):
        exact_name = _is_exact_name(mention, c)
        exact_alias = _is_exact_alias(mention, c)
        qualifier_cov = _qualifier_coverage(mention, c)
        status_rank = STATUS_RANK.get(getattr(c, "contract_status", "INFERRED").upper(), 0)
        param_satisfiable = _is_param_satisfiable(c)
        priority = int(getattr(c, "default_priority", 0))
        score = float(getattr(c, "score", 0.0))

        return (
            -exact_name,
            -exact_alias,
            -qualifier_cov,
            -status_rank,
            -param_satisfiable,
            -priority,
            -score,
            str(c.key),
        )

    return sorted(unique_candidates, key=sort_key)
