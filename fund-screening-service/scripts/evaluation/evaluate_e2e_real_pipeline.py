from __future__ import annotations

import argparse
import asyncio
import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from app.config import Settings
from app.evaluation.loader import load_golden_dataset
from app.evaluation.metrics import compute_dsl_match, compute_clarification_match
from app.evaluation.schema import GoldenCase
from app.models.dsl import FundScreeningDsl
from app.runtime import build_production_compiler


def resolve_catalog_paths():
    service_root = Path(__file__).resolve().parents[2]
    repo_root = service_root
    while repo_root.parent != repo_root:
        if (repo_root / "docs" / "temp" / "metrics.json").exists():
            break
        repo_root = repo_root.parent
    metrics_path = repo_root / "docs" / "temp" / "metrics.json"
    sectors_path = repo_root / "docs" / "temp" / "sectors.json"
    entity_path = service_root / "tests" / "fixtures" / "entities-small.json"
    return str(metrics_path), str(sectors_path), str(entity_path)


class RealPipelineEvaluator:
    def __init__(self) -> None:
        metrics_p, sectors_p, entity_p = resolve_catalog_paths()
        self.settings = Settings(
            metrics_catalog_path=metrics_p,
            sectors_catalog_path=sectors_p,
            entity_catalog_path=entity_p,
        )
        self.compiler = build_production_compiler(self.settings)

    async def evaluate_case(self, case: GoldenCase) -> dict[str, Any]:
        from app.graph.checkpoint import make_graph_config
        from app.graph.state import empty_state

        t0 = time.perf_counter()
        try:
            state = empty_state(case.query)
            config = make_graph_config()
            graph_res = await self.compiler.graph.ainvoke(state, config=config)
            latency_ms = (time.perf_counter() - t0) * 1000.0
            error = None
        except Exception as exc:
            latency_ms = (time.perf_counter() - t0) * 1000.0
            graph_res = {}
            error = str(exc)

        actual_dsl = graph_res.get("final_dsl") or graph_res.get("draft_dsl")
        actual_clar = graph_res.get("clarification")
        actual_route = graph_res.get("route", "unknown")
        actual_trace = {
            "route": actual_route,
            "repairs": graph_res.get("repairs", []),
            "candidate_count": len(graph_res.get("candidate_pool", [])),
        }

        actual_dsl_dict = actual_dsl.model_dump(by_alias=True) if hasattr(actual_dsl, "model_dump") else actual_dsl
        actual_clar_dict = actual_clar.model_dump(by_alias=True) if hasattr(actual_clar, "model_dump") else actual_clar

        passed = False
        outcome_match = False
        dsl_match_info = {}
        clar_match_info = {}

        if error:
            failure_reason = f"Execution Error: {error}"
        elif case.expected_outcome == "DSL":
            outcome_match = actual_dsl is not None
            if actual_dsl:
                if case.expected_dsl:
                    dsl_match_info = compute_dsl_match(actual_dsl_dict, case.expected_dsl)
                    passed = bool(dsl_match_info.get("structural_match", False))
                else:
                    passed = True
            failure_reason = None if passed else ("Unexpected clarification" if actual_clar else "Missing DSL output")
        elif case.expected_outcome == "CLARIFICATION":
            outcome_match = actual_clar is not None
            if actual_clar:
                clar_match_info = compute_clarification_match(actual_clar_dict, case.expected_clarification)
                passed = bool(clar_match_info.get("matched", False))
            else:
                passed = False
            failure_reason = None if passed else ("Generated DSL instead of Clarification" if actual_dsl else "No clarification output")
        else:
            passed = True
            failure_reason = None

        return {
            "case_id": case.id,
            "category": case.category,
            "difficulty": case.difficulty,
            "query": case.query,
            "expected_outcome": case.expected_outcome,
            "actual_outcome": "DSL" if actual_dsl else ("CLARIFICATION" if actual_clar else "ERROR"),
            "route": actual_trace.get("route", "unknown"),
            "passed": passed,
            "outcome_match": outcome_match,
            "latency_ms": latency_ms,
            "dsl_match": dsl_match_info,
            "clar_match": clar_match_info,
            "failure_reason": failure_reason,
            "actual_dsl": actual_dsl_dict,
            "actual_clarification": actual_clar_dict,
            "trace": actual_trace,
        }


def render_markdown_report(results: list[dict[str, Any]], output_path: Path) -> None:
    total = len(results)
    passed_cnt = sum(1 for r in results if r["passed"])
    pass_rate = passed_cnt / total if total > 0 else 0.0
    avg_latency = sum(r["latency_ms"] for r in results) / total if total > 0 else 0.0

    lines = [
        "# 基金筛选真实 Production Pipeline 全流程端到端评测报告",
        "",
        f"- **评测时间**: {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}",
        "- **评测引擎**: 生产级 LangGraph 编译流 (`build_production_compiler`)",
        "- **底层支撑**: PostgreSQL 16 pgvector (1024 维) + Ollama `qwen3-embedding` + 本地 `codex-bridge` (agy-cli / gemini-3.8-flash-low)",
        "",
        "## 1. 全流程汇总指标",
        "",
        f"- **评测样本总量**: {total}",
        f"- **端到端完全通过 (Passed)**: {passed_cnt} / {total} (**{pass_rate * 100:.1f}%**)",
        f"- **预期形态吻合率 (Outcome Match)**: {sum(1 for r in results if r['outcome_match']) / total * 100:.1f}%",
        f"- **端到端平均执行耗时**: {avg_latency / 1000.0:.2f}s",
        "",
        "## 2. 核心案例执行明细表",
        "",
        "| Case ID | 自然语言 Query | 期望形态 | 实际形态 | 命中路由 | 耗时 (s) | 评测结论 |",
        "| :--- | :--- | :--- | :--- | :--- | :--- | :--- |",
    ]

    for r in results:
        status_badge = "PASSED" if r["passed"] else "FAILED"
        lines.append(
            f"| `{r['case_id']}` | {r['query']} | `{r['expected_outcome']}` | `{r['actual_outcome']}` | "
            f"`{r['route']}` | {r['latency_ms']/1000.0:.2f}s | **{status_badge}** |"
        )

    lines.append("")
    lines.append("## 3. 详细生成结果与语法树剖析")
    lines.append("")

    for r in results:
        lines.extend([
            f"### 案例: `{r['case_id']}`",
            f"- **用户 Query**: `{r['query']}`",
            f"- **执行路由**: `{r['route']}` | **耗时**: {r['latency_ms']/1000.0:.2f}s",
            f"- **评测状态**: {'PASSED' if r['passed'] else 'FAILED'}",
        ])
        if r["failure_reason"]:
            lines.append(f"- **未通过原因**: {r['failure_reason']}")
        if r["actual_dsl"]:
            lines.append("- **生成的真实 DSL**:")
            lines.append("```json")
            lines.append(json.dumps(r["actual_dsl"], ensure_ascii=False, indent=2))
            lines.append("```")
        if r["actual_clarification"]:
            lines.append("- **生成的澄清请求**:")
            lines.append("```json")
            lines.append(json.dumps(r["actual_clarification"], ensure_ascii=False, indent=2))
            lines.append("```")
        lines.append("")

    output_path.write_text("\n".join(lines), encoding="utf-8")


async def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate Real E2E Production Pipeline on Golden Scenarios")
    parser.add_argument("--dataset-file", type=Path, default=Path(__file__).resolve().parents[2] / "data" / "golden" / "research-scenarios.jsonl")
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parents[2] / "data" / "golden" / "reports")
    parser.add_argument("--limit", type=int, default=None)
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    cases = load_golden_dataset(args.dataset_file)
    if args.limit:
        cases = cases[:args.limit]

    print("==================================================================")
    print("       Starting Real Production Pipeline E2E Evaluation           ")
    print("==================================================================")
    print(f"Loaded {len(cases)} real scenarios from {args.dataset_file.name}...")

    evaluator = RealPipelineEvaluator()
    results = []
    for idx, case in enumerate(cases, 1):
        print(f"[{idx}/{len(cases)}] Running E2E for case '{case.id}' ('{case.query}')...")
        res = await evaluator.evaluate_case(case)
        status = "PASSED" if res["passed"] else "FAILED"
        print(f"       -> Route: {res['route']} | Latency: {res['latency_ms']/1000.0:.2f}s | Result: {status}")
        results.append(res)

    md_path = args.output_dir / "e2e_real_pipeline_report.md"
    json_path = args.output_dir / "e2e_real_pipeline_report.json"

    render_markdown_report(results, md_path)
    json_path.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")

    total = len(results)
    passed = sum(1 for r in results if r["passed"])
    print("\n======================= E2E Evaluation Summary =======================")
    print(f"Total Cases:  {total}")
    print(f"Passed Cases: {passed}")
    print(f"Pass Rate:    {passed / total * 100:.1f}%")
    print(f"Reports saved to:")
    print(f"  - Markdown: {md_path}")
    print(f"  - JSON:     {json_path}")
    print("======================================================================\n")


if __name__ == "__main__":
    asyncio.run(main())
