from __future__ import annotations

from collections import Counter
from datetime import datetime, timezone
from typing import Any

from app.evaluation.models import ErrorReason


def classify_failure(case) -> ErrorReason | None:
    """把未命中归因为可直接执行的数据治理动作 (Legacy compatibility)。"""
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
    """Legacy report generator."""
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


def _extract_data(result: Any) -> dict[str, Any]:
    if hasattr(result, "to_dict"):
        return result.to_dict()
    if hasattr(result, "model_dump"):
        return result.model_dump()
    if isinstance(result, dict):
        return result
    return {}


def render_json_report(result: Any, metadata: dict[str, Any] | None = None) -> dict[str, Any]:
    data = _extract_data(result)
    ch_metrics = data.get("channel_metrics") or data.get("channel_summary") or {}
    e2e_metrics = data.get("e2e_metrics") or {**data.get("dsl_summary", {}), **data.get("clarification_summary", {})}
    failure_attr = data.get("failure_attribution") or data.get("failure_attribution_counts") or {}
    backlog = data.get("actionable_backlog") or []

    return {
        "dataset_version": data.get("dataset_version", "v1.0"),
        "total_cases": data.get("total_cases", 0),
        "passed_cases": data.get("passed_cases", 0),
        "failed_cases": data.get("failed_cases", 0),
        "pass_rate": data.get("pass_rate", 0.0),
        "phased_metrics": {
            "recall_hit_rate": data.get("recall_hit_rate", 0.0),
            "binding_accuracy": data.get("binding_accuracy", 0.0),
            "format_valid_rate": data.get("format_valid_rate", 0.0),
        },
        "difficulty_breakdown": data.get("difficulty_breakdown", {}),
        "channel_metrics": ch_metrics,
        "e2e_metrics": e2e_metrics,
        "failure_attribution": failure_attr,
        "actionable_backlog": backlog,
        "metadata": metadata or {},
        "timestamp": data.get("timestamp", datetime.now(timezone.utc).isoformat()),
    }


def render_markdown_report(result: Any, metadata: dict[str, Any] | None = None) -> str:
    data = render_json_report(result, metadata)

    lines: list[str] = [
        "# 基金筛选 pgvector 与 DSL 金测试评估报告",
        "",
        f"- **评测时间**: {data.get('timestamp')}",
        f"- **样本版本**: {data.get('dataset_version')}",
        f"- **总用例数**: {data.get('total_cases')}",
        f"- **通过用例**: {data.get('passed_cases')}",
        f"- **失败用例**: {data.get('failed_cases')}",
        f"- **总体通过率**: {data.get('pass_rate', 0.0) * 100:.1f}%",
        "",
        "## 1. 多通道检索指标 (Condition-Level IR)",
        "",
        "| 通道 | Hit@1 | Hit@3 | Hit@5 | Hit@10 | MRR | nDCG@3 | Precision@3 | Recall@3 |",
        "| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |",
    ]

    ch_metrics = data.get("channel_metrics", {})
    if ch_metrics:
        for ch_name, m in ch_metrics.items():
            hit1 = f"{m.get('hit@1', 0.0) * 100:.1f}%"
            hit3 = f"{m.get('hit@3', 0.0) * 100:.1f}%"
            hit5 = f"{m.get('hit@5', 0.0) * 100:.1f}%"
            hit10 = f"{m.get('hit@10', 0.0) * 100:.1f}%"
            mrr = f"{m.get('mrr', 0.0):.3f}"
            ndcg3 = f"{m.get('ndcg@3', 0.0):.3f}"
            p3 = f"{m.get('precision@3', 0.0):.3f}"
            r3 = f"{m.get('recall@3', 0.0) * 100:.1f}%"
            lines.append(f"| `{ch_name}` | {hit1} | {hit3} | {hit5} | {hit10} | {mrr} | {ndcg3} | {p3} | {r3} |")
    else:
        lines.append("| *(无通道指标数据)* | - | - | - | - | - | - | - | - |")

    lines.extend([
        "",
        "## 2. 端到端 DSL 与澄清指标 (E2E Metrics)",
        "",
        "| 指标项 | 达成数值 |",
        "| :--- | :--- |",
    ])

    e2e_m = data.get("e2e_metrics", {})
    if e2e_m:
        for k, v in e2e_m.items():
            val_str = f"{v * 100:.1f}%" if isinstance(v, float) and v <= 1.0 else str(v)
            lines.append(f"| {k} | {val_str} |")
    else:
        lines.append("| *(无端到端指标数据)* | - |")

    lines.extend([
        "",
        "## 3. 失败根因归因分布 (7-Class Failure Attribution)",
        "",
        "| 归因类别 | 出现频次 | 占比 | 治理动作重点 |",
        "| :--- | :--- | :--- | :--- |",
    ])

    failure_attr = data.get("failure_attribution", {})
    total_failures = max(sum(failure_attr.values()), 1)
    if failure_attr:
        for reason, cnt in sorted(failure_attr.items(), key=lambda x: x[1], reverse=True):
            pct = f"{(cnt / total_failures) * 100:.1f}%"
            focus = {
                "alias_missing": "补充指标/板块/实体同义别名表",
                "embedding_text_insufficient": "扩充 candidate embedding 业务描述上下文",
                "vector_recall_weak": "调整向量相似度阈值或增加 Top-K 深度",
                "rerank_issue": "调优 RRF 融合权重或重排策略",
                "plan_error": "优化 SemanticPlan 解析 Prompt 与少样本示例",
                "adjudication_error": "调优 Adjudication 裁决 Prompt 与参数绑定规则",
                "validator_error": "排查 AST 语法树生成与语义校验器拦截规则",
            }.get(reason, "持续优化")
            lines.append(f"| `{reason}` | {cnt} | {pct} | {focus} |")
    else:
        lines.append("| *(无失败用例)* | 0 | 0.0% | 保持良好状态 |")

    lines.extend([
        "",
        "## 4. 可执行数据治理与模型调优待办清单 (Actionable Backlog)",
        "",
        "| 案例ID | 归因类别 | 问句 (Query) | 推荐整改动作 (Remediation) |",
        "| :--- | :--- | :--- | :--- |",
    ])

    backlog = data.get("actionable_backlog", [])
    if backlog:
        for item in backlog[:50]:  # Cap at top 50 in markdown report
            cid = item.get("case_id", "")
            reason = item.get("reason", "")
            query = item.get("query", "").replace("|", "/")
            action = (item.get("action") or item.get("expected") or "").replace("|", "/")
            lines.append(f"| `{cid}` | `{reason}` | {query} | {action} |")
    else:
        lines.append("| *(暂无待办事项)* | - | - | - |")

    return "\n".join(lines)
