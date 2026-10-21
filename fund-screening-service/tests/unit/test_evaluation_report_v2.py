from __future__ import annotations

import pytest
from app.evaluation.report import render_json_report, render_markdown_report


def test_markdown_and_json_report_generation():
    mock_summary = {
        "dataset_version": "v1.0",
        "total_cases": 10,
        "channel_metrics": {
            "merged": {"hit@1": 0.8, "hit@3": 0.9, "mrr": 0.85, "ndcg@3": 0.88},
            "metric_pgvector": {"hit@1": 0.75, "hit@3": 0.85, "mrr": 0.80, "ndcg@3": 0.82},
        },
        "e2e_metrics": {
            "dsl_structural_match_rate": 0.80,
            "clarification_match_rate": 1.0,
        },
        "failure_attribution": {
            "alias_missing": 1,
            "vector_recall_weak": 1,
        },
        "actionable_backlog": [
            {
                "case_id": "c1",
                "reason": "alias_missing",
                "query": "近三年年化",
                "expected": "f_return_3y",
                "action": "Add alias '近三年年化' to f_return_3y in metrics catalog",
            }
        ],
    }
    md = render_markdown_report(mock_summary)
    assert "# 基金筛选 pgvector 与 DSL 金测试评估报告" in md
    assert "Hit@1" in md
    assert "alias_missing" in md

    js = render_json_report(mock_summary)
    assert js["total_cases"] == 10
    assert len(js["actionable_backlog"]) == 1
