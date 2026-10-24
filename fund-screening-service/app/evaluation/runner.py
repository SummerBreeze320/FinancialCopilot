from __future__ import annotations

import inspect
import time
from datetime import datetime, timezone
from typing import Any
from pydantic import BaseModel, Field

from app.evaluation.attribution import (
    ActionableReason,
    classify_detailed_failure,
    get_remediation_suggestion,
)
from app.evaluation.metrics import (
    compute_clarification_match,
    compute_dsl_match,
    compute_ranking_metrics,
    compute_target_accuracy,
)
from app.evaluation.schema import GoldenCase


class CaseEvaluationResult(BaseModel):
    case_id: str
    query: str
    category: str
    expected_outcome: str
    success: bool
    candidate_selection_accuracy: float = 0.0
    operator_value_unit_accuracy: float = 0.0
    parameter_accuracy: float = 0.0
    executable_dsl_rate: float = 0.0
    channel_recalls: dict[str, list[str]] = Field(default_factory=dict)
    channel_metrics: dict[str, dict[str, float]] = Field(default_factory=dict)
    dsl_match: dict[str, Any] = Field(default_factory=dict)
    clarification_match: dict[str, bool] = Field(default_factory=dict)
    failure_reason: str | None = None
    remediation_suggestion: str | None = None
    recall_hit: bool = True
    binding_correct: bool = True
    format_valid: bool = True
    latency_ms: float = 0.0


class DatasetEvaluationResult(BaseModel):
    total_cases: int
    passed_cases: int
    failed_cases: int
    pass_rate: float
    raw_recall_at_1: float = 0.0
    raw_recall_at_3: float = 0.0
    final_candidate_selection_accuracy: float = 0.0
    operator_value_unit_accuracy: float = 0.0
    parameter_accuracy: float = 0.0
    executable_dsl_rate: float = 0.0
    channel_summary: dict[str, dict[str, float]] = Field(default_factory=dict)
    dsl_summary: dict[str, float] = Field(default_factory=dict)
    clarification_summary: dict[str, float] = Field(default_factory=dict)
    recall_hit_rate: float = 0.0
    binding_accuracy: float = 0.0
    format_valid_rate: float = 0.0
    difficulty_breakdown: dict[str, dict[str, float]] = Field(default_factory=dict)
    failure_attribution_counts: dict[str, int] = Field(default_factory=dict)
    failures: list[CaseEvaluationResult] = Field(default_factory=list)
    actionable_backlog: list[dict[str, Any]] = Field(default_factory=list)
    timestamp: str = Field(default_factory=lambda: datetime.now(timezone.utc).isoformat())

    def to_dict(self) -> dict[str, Any]:
        return {
            "total_cases": self.total_cases,
            "passed_cases": self.passed_cases,
            "failed_cases": self.failed_cases,
            "pass_rate": self.pass_rate,
            "raw_recall_at_1": self.raw_recall_at_1,
            "raw_recall_at_3": self.raw_recall_at_3,
            "final_candidate_selection_accuracy": self.final_candidate_selection_accuracy,
            "operator_value_unit_accuracy": self.operator_value_unit_accuracy,
            "parameter_accuracy": self.parameter_accuracy,
            "executable_dsl_rate": self.executable_dsl_rate,
            "channel_metrics": self.channel_summary,
            "channel_summary": self.channel_summary,
            "dsl_summary": self.dsl_summary,
            "clarification_summary": self.clarification_summary,
            "recall_hit_rate": self.recall_hit_rate,
            "binding_accuracy": self.binding_accuracy,
            "format_valid_rate": self.format_valid_rate,
            "difficulty_breakdown": self.difficulty_breakdown,
            "e2e_metrics": {
                **self.dsl_summary,
                **self.clarification_summary,
                "final_candidate_selection_accuracy": self.final_candidate_selection_accuracy,
                "operator_value_unit_accuracy": self.operator_value_unit_accuracy,
                "parameter_accuracy": self.parameter_accuracy,
                "executable_dsl_rate": self.executable_dsl_rate,
            },
            "failure_attribution": self.failure_attribution_counts,
            "failure_attribution_counts": self.failure_attribution_counts,
            "actionable_backlog": self.actionable_backlog,
            "failures": [f.model_dump() for f in self.failures],
            "timestamp": self.timestamp,
        }

    def __getitem__(self, item: str) -> Any:
        return self.to_dict()[item]

    def __contains__(self, item: str) -> bool:
        return item in self.to_dict()


class DefaultEvaluationPipeline:
    def __init__(self, graph: Any = None) -> None:
        if graph is None:
            from app.main import _build_default_graph
            graph = _build_default_graph()
        self._graph = graph

    async def run(self, query: str) -> dict[str, Any]:
        from app.graph.checkpoint import make_graph_config
        from app.graph.state import empty_state

        state = empty_state(query)
        config = make_graph_config()
        final_state = state
        try:
            async for update in self._graph.astream(state, config=config, stream_mode="updates"):
                for _, node_update in update.items():
                    final_state = {**final_state, **node_update}
        except Exception as e:
            final_state["validator_error"] = str(e)
            final_state["error_type"] = "validator" if "Validation" in type(e).__name__ else "runtime"

        pool = final_state.get("candidate_pool") or []
        channel_recalls: dict[str, list[str]] = {
            "merged": [],
            "metric_pgvector": [],
            "scope_pgvector": [],
            "entity_lexical": [],
        }
        for item in pool:
            key = item.get("key") if isinstance(item, dict) else getattr(item, "key", None)
            domain = item.get("domain") if isinstance(item, dict) else getattr(item, "domain", None)
            if key:
                channel_recalls["merged"].append(key)
                if domain == "metric":
                    channel_recalls["metric_pgvector"].append(key)
                elif domain == "scope":
                    channel_recalls["scope_pgvector"].append(key)
                elif domain == "entity":
                    channel_recalls["entity_lexical"].append(key)

        channel_recalls["pgvector"] = channel_recalls["metric_pgvector"] + channel_recalls["scope_pgvector"]
        channel_recalls["lexical"] = channel_recalls["entity_lexical"]

        return {
            "channel_recalls": channel_recalls,
            "plan": final_state.get("semantic_plan") or final_state.get("plan"),
            "adjudication": final_state.get("adjudication"),
            "dsl": final_state.get("final_dsl") or final_state.get("draft_dsl"),
            "clarification": final_state.get("clarification"),
            "validator_error": final_state.get("validator_error"),
            "error_type": final_state.get("error_type"),
        }


class GoldenEvaluator:
    def __init__(self, pipeline: Any = None) -> None:
        if pipeline is None:
            pipeline = DefaultEvaluationPipeline()
        self.pipeline = pipeline

    async def _execute_pipeline(self, query: str) -> dict[str, Any]:
        if self.pipeline is None:
            raise RuntimeError("No pipeline provided to GoldenEvaluator")

        try:
            if hasattr(self.pipeline, "run"):
                res = self.pipeline.run(query)
            elif callable(self.pipeline):
                res = self.pipeline(query)
            else:
                raise TypeError("Pipeline must have a .run() method or be callable")

            if inspect.isawaitable(res):
                res = await res
            return res if isinstance(res, dict) else {}
        except Exception as e:
            return {
                "validator_error": str(e),
                "error_type": "validator" if "Validation" in type(e).__name__ else "runtime",
                "error_message": str(e),
                "dsl": None,
                "clarification": None,
            }

    async def evaluate_case(self, case: GoldenCase) -> CaseEvaluationResult:
        start_time = time.perf_counter()
        raw_res = await self._execute_pipeline(case.query)
        latency_ms = (time.perf_counter() - start_time) * 1000.0

        channel_recalls = raw_res.get("channel_recalls", {})
        if not isinstance(channel_recalls, dict):
            channel_recalls = {}

        # Aggregate relevant keys from all expected conditions
        relevant_keys: list[str] = []
        primary_key: str | None = None
        for cond in case.expected_conditions:
            for k in cond.relevant_candidate_keys:
                if k not in relevant_keys:
                    relevant_keys.append(k)
            if not primary_key and cond.primary_candidate_key:
                primary_key = cond.primary_candidate_key

        channel_metrics: dict[str, dict[str, float]] = {}
        for ch_name, recalled in channel_recalls.items():
            channel_metrics[ch_name] = compute_ranking_metrics(
                recalled_keys=recalled,
                relevant_keys=relevant_keys,
                primary_key=primary_key,
                k_list=(1, 3, 5, 10),
            )

        actual_dsl = raw_res.get("dsl")
        if actual_dsl is not None and hasattr(actual_dsl, "model_dump"):
            actual_dsl = actual_dsl.model_dump(by_alias=True)

        actual_clar = raw_res.get("clarification")
        if actual_clar is not None and hasattr(actual_clar, "model_dump"):
            actual_clar = actual_clar.model_dump(by_alias=True)

        dsl_match: dict[str, Any] = {}
        clar_match: dict[str, bool] = {}
        success = False

        target_acc = compute_target_accuracy(actual_dsl, getattr(case, "expected", None))

        if case.expected_outcome == "DSL":
            if getattr(case, "expected", None) is not None:
                success = bool(
                    target_acc["candidate_selection_accuracy"] == 1.0
                    and target_acc["operator_value_unit_accuracy"] == 1.0
                    and target_acc["parameter_accuracy"] == 1.0
                    and target_acc["executable_dsl_rate"] == 1.0
                )
            elif case.expected_dsl:
                dsl_match = compute_dsl_match(actual_dsl, case.expected_dsl)
                success = bool(dsl_match.get("structural_match", False))
            else:
                # Ground truth scaffold check: candidate hit + dsl produced
                if actual_dsl:
                    if relevant_keys:
                        merged = channel_recalls.get("merged", [])
                        success = any(k in merged for k in relevant_keys)
                    else:
                        success = True
                else:
                    success = False
                dsl_match = compute_dsl_match(actual_dsl, actual_dsl) if actual_dsl else {}
        elif case.expected_outcome == "CLARIFICATION":
            clar_match = compute_clarification_match(actual_clar, case.expected_clarification)
            success = bool(clar_match.get("matched", False))
        else:
            success = True

        failure_reason: str | None = None
        remediation_suggestion: str | None = None
        if not success:
            reason_enum = classify_detailed_failure(case, channel_recalls, raw_res)
            failure_reason = reason_enum.value if hasattr(reason_enum, "value") else str(reason_enum)
            remediation_suggestion = get_remediation_suggestion(reason_enum, case)

        fmt_valid = (target_acc["executable_dsl_rate"] > 0) if case.expected_outcome == "DSL" else True
        bind_corr = (target_acc["candidate_selection_accuracy"] >= 1.0)
        rec_hit = any(v.get("hit@10", 0.0) >= 1.0 for v in channel_metrics.values()) if channel_metrics else True

        return CaseEvaluationResult(
            case_id=case.id,
            query=case.query,
            category=case.category,
            expected_outcome=case.expected_outcome,
            success=success,
            candidate_selection_accuracy=target_acc["candidate_selection_accuracy"],
            operator_value_unit_accuracy=target_acc["operator_value_unit_accuracy"],
            parameter_accuracy=target_acc["parameter_accuracy"],
            executable_dsl_rate=target_acc["executable_dsl_rate"],
            channel_recalls=channel_recalls,
            channel_metrics=channel_metrics,
            dsl_match=dsl_match,
            clarification_match=clar_match,
            failure_reason=failure_reason,
            remediation_suggestion=remediation_suggestion,
            recall_hit=rec_hit,
            binding_correct=bind_corr,
            format_valid=fmt_valid,
            latency_ms=latency_ms,
        )

    async def evaluate_dataset(self, cases: list[GoldenCase]) -> DatasetEvaluationResult:
        results: list[CaseEvaluationResult] = []
        for case in cases:
            res = await self.evaluate_case(case)
            results.append(res)

        total = len(results)
        passed = sum(1 for r in results if r.success)
        failed = total - passed
        pass_rate = (passed / total) if total > 0 else 0.0

        # Channel summaries
        all_channels: set[str] = set()
        for r in results:
            all_channels.update(r.channel_metrics.keys())

        channel_summary: dict[str, dict[str, float]] = {}
        for ch in all_channels:
            metric_keys = ["hit@1", "hit@3", "hit@5", "hit@10", "precision@1", "precision@3", "precision@5", "recall@1", "recall@3", "recall@5", "mrr", "ndcg@3", "ndcg@5"]
            ch_avg: dict[str, float] = {}
            for mk in metric_keys:
                vals = [r.channel_metrics[ch][mk] for r in results if ch in r.channel_metrics and mk in r.channel_metrics[ch]]
                ch_avg[mk] = (sum(vals) / len(vals)) if vals else 0.0
            channel_summary[ch] = ch_avg

        # DSL summary
        dsl_cases = [r for r in results if r.expected_outcome == "DSL"]
        dsl_summary: dict[str, float] = {}
        if dsl_cases:
            exact = sum(1 for r in dsl_cases if r.dsl_match.get("exact_match"))
            structural = sum(1 for r in dsl_cases if r.dsl_match.get("structural_match"))
            dsl_summary["exact_match_rate"] = exact / len(dsl_cases)
            dsl_summary["structural_match_rate"] = structural / len(dsl_cases)

        raw_recall_1 = channel_summary.get("merged", {}).get("recall@1", 0.0)
        raw_recall_3 = channel_summary.get("merged", {}).get("recall@3", 0.0)
        final_cand_acc = (sum(r.candidate_selection_accuracy for r in dsl_cases) / len(dsl_cases)) if dsl_cases else 0.0
        op_val_unit_acc = (sum(r.operator_value_unit_accuracy for r in dsl_cases) / len(dsl_cases)) if dsl_cases else 0.0
        param_acc = (sum(r.parameter_accuracy for r in dsl_cases) / len(dsl_cases)) if dsl_cases else 0.0
        exec_rate = (sum(r.executable_dsl_rate for r in dsl_cases) / len(dsl_cases)) if dsl_cases else 0.0

        # Clarification summary
        clar_cases = [r for r in results if r.expected_outcome == "CLARIFICATION"]
        clar_summary: dict[str, float] = {}
        if clar_cases:
            matched = sum(1 for r in clar_cases if r.clarification_match.get("matched"))
            code_matched = sum(1 for r in clar_cases if r.clarification_match.get("code_matched"))
            clar_summary["matched_rate"] = matched / len(clar_cases)
            clar_summary["code_matched_rate"] = code_matched / len(clar_cases)

        # Failure attribution breakdown & backlog
        failure_counts: dict[str, int] = {}
        failures = [r for r in results if not r.success]
        backlog: list[dict[str, Any]] = []
        for f in failures:
            if f.failure_reason:
                failure_counts[f.failure_reason] = failure_counts.get(f.failure_reason, 0) + 1
            backlog.append({
                "case_id": f.case_id,
                "reason": f.failure_reason or "unknown",
                "query": f.query,
                "expected": f.remediation_suggestion or "",
                "action": f.remediation_suggestion or "",
            })

        rec_hit_rate = (sum(1 for r in results if r.recall_hit) / total) if total > 0 else 0.0
        bind_acc = (sum(1 for r in results if r.binding_correct) / total) if total > 0 else 0.0
        fmt_valid_rate = (sum(1 for r in results if r.format_valid) / total) if total > 0 else 0.0

        diff_cases: dict[str, list[CaseEvaluationResult]] = {}
        for r, c in zip(results, cases):
            diff = getattr(c, "difficulty", "clear")
            diff_cases.setdefault(diff, []).append(r)

        diff_breakdown: dict[str, dict[str, float]] = {}
        for diff, d_results in diff_cases.items():
            d_total = len(d_results)
            d_passed = sum(1 for r in d_results if r.success)
            diff_breakdown[diff] = {
                "total": float(d_total),
                "passed": float(d_passed),
                "pass_rate": (d_passed / d_total) if d_total > 0 else 0.0,
            }

        return DatasetEvaluationResult(
            total_cases=total,
            passed_cases=passed,
            failed_cases=failed,
            pass_rate=pass_rate,
            raw_recall_at_1=raw_recall_1,
            raw_recall_at_3=raw_recall_3,
            final_candidate_selection_accuracy=final_cand_acc,
            operator_value_unit_accuracy=op_val_unit_acc,
            parameter_accuracy=param_acc,
            executable_dsl_rate=exec_rate,
            channel_summary=channel_summary,
            dsl_summary=dsl_summary,
            clarification_summary=clar_summary,
            recall_hit_rate=rec_hit_rate,
            binding_accuracy=bind_acc,
            format_valid_rate=fmt_valid_rate,
            difficulty_breakdown=diff_breakdown,
            failure_attribution_counts=failure_counts,
            failures=failures,
            actionable_backlog=backlog,
        )
