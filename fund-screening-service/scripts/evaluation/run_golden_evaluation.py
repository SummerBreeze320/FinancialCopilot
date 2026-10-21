from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path

from app.evaluation.loader import load_golden_dataset
from app.evaluation.report import render_json_report, render_markdown_report
from app.evaluation.runner import GoldenEvaluator


async def run_evaluation(
    dataset_dir: Path,
    output_dir: Path,
    category: str | None = None,
    limit: int | None = None,
) -> None:
    cases = load_golden_dataset(dataset_dir)
    if category:
        cases = [c for c in cases if c.category == category]
    if limit and limit > 0:
        cases = cases[:limit]

    print(f"Loaded {len(cases)} golden cases from {dataset_dir}...")
    evaluator = GoldenEvaluator()
    summary = await evaluator.evaluate_dataset(cases)

    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / "evaluation_report.json"
    md_path = output_dir / "evaluation_report.md"

    json_report = render_json_report(summary)
    json_path.write_text(json.dumps(json_report, ensure_ascii=False, indent=2), encoding="utf-8")

    md_report = render_markdown_report(summary)
    md_path.write_text(md_report, encoding="utf-8")

    print(f"\n================ Evaluation Finished ================")
    print(f"Total Cases:  {summary.total_cases}")
    print(f"Passed Cases: {summary.passed_cases}")
    print(f"Failed Cases: {summary.failed_cases}")
    print(f"Pass Rate:    {summary.pass_rate * 100:.1f}%")
    print(f"Reports saved to:")
    print(f"  - Markdown: {md_path}")
    print(f"  - JSON:     {json_path}")
    if summary.failure_attribution_counts:
        print("\nFailure Attribution Breakdown:")
        for r, cnt in summary.failure_attribution_counts.items():
            print(f"  - {r}: {cnt}")
    print("=====================================================\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run Fund Screening PGVector and DSL Golden Evaluation")
    parser.add_argument(
        "--dataset-dir",
        type=Path,
        default=Path(__file__).resolve().parents[2] / "data" / "golden",
        help="Directory containing golden .jsonl files",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(__file__).resolve().parents[2] / "data" / "golden" / "reports",
        help="Directory to save evaluation reports",
    )
    parser.add_argument(
        "--category",
        type=str,
        default=None,
        help="Filter evaluation cases by category",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Max number of cases to evaluate",
    )
    args = parser.parse_args()

    asyncio.run(
        run_evaluation(
            dataset_dir=args.dataset_dir,
            output_dir=args.output_dir,
            category=args.category,
            limit=args.limit,
        )
    )


if __name__ == "__main__":
    main()
