from __future__ import annotations

from pathlib import Path
import pytest

from app.evaluation.loader import load_golden_dataset
from app.evaluation.report import render_json_report, render_markdown_report
from app.evaluation.runner import GoldenEvaluator


@pytest.mark.asyncio
async def test_full_golden_evaluation_pipeline():
    golden_dir = Path(__file__).resolve().parents[2] / "data" / "golden"
    cases = load_golden_dataset(golden_dir)
    assert len(cases) >= 5

    # Run on a representative slice (e.g. 20 cases) to keep unit/integration fast
    eval_cases = cases[:20]
    evaluator = GoldenEvaluator()
    summary = await evaluator.evaluate_dataset(eval_cases)

    assert "channel_metrics" in summary
    assert "e2e_metrics" in summary
    assert "failure_attribution" in summary

    md_report = render_markdown_report(summary)
    assert len(md_report) > 100
    assert "# 基金筛选 pgvector 与 DSL 金测试评估报告" in md_report

    json_report = render_json_report(summary)
    assert json_report["total_cases"] == len(eval_cases)
    assert "pass_rate" in json_report
