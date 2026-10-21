from __future__ import annotations

import pytest
from app.evaluation.attribution import ActionableReason, classify_detailed_failure, get_remediation_suggestion
from app.evaluation.runner import CaseEvaluationResult, DatasetEvaluationResult, GoldenEvaluator
from app.evaluation.schema import ExpectedCondition, GoldenCase


def test_failure_attribution_alias_missing():
    case = GoldenCase.model_validate({
        "id": "test-alias",
        "category": "metric",
        "query": "近三年年化",
        "expectedOutcome": "DSL",
        "expectedConditions": [
            ExpectedCondition(
                mention="近三年年化",
                domain="metric",
                relevantCandidateKeys=["f_return_3y"],
                primaryCandidateKey="f_return_3y",
            )
        ],
    })
    # Lexical and vector both missed because alias not indexed
    channel_recalls = {"pgvector": [], "lexical": [], "merged": []}
    e2e = {"dsl": None, "clarification": None}
    reason = classify_detailed_failure(case, channel_recalls, e2e)
    assert reason == ActionableReason.ALIAS_MISSING
    suggestion = get_remediation_suggestion(reason, case)
    assert "alias" in suggestion.lower() or "别名" in suggestion


def test_failure_attribution_plan_error():
    case = GoldenCase.model_validate({
        "id": "test-plan",
        "category": "scenario",
        "query": "规模大于10亿或者成立超过5年",
        "expectedOutcome": "DSL",
        "expectedConditions": [
            ExpectedCondition(mention="规模大于10亿", domain="metric", relevantCandidateKeys=["f_scale"]),
            ExpectedCondition(mention="成立超过5年", domain="metric", relevantCandidateKeys=["f_year"]),
        ],
    })
    channel_recalls = {"merged": ["f_scale", "f_year"]}
    # Plan error: only 1 condition extracted instead of 2
    e2e = {"plan": {"conditions": [{"mention": "规模大于10亿"}]}, "dsl": None}
    reason = classify_detailed_failure(case, channel_recalls, e2e)
    assert reason == ActionableReason.PLAN_ERROR


def test_failure_attribution_adjudication_error():
    case = GoldenCase.model_validate({
        "id": "test-adj",
        "category": "metric",
        "query": "规模大于10亿",
        "expectedOutcome": "DSL",
        "expectedConditions": [
            ExpectedCondition(mention="规模大于10亿", domain="metric", relevantCandidateKeys=["f_info_fundscale"]),
        ],
    })
    # Merged has the candidate, plan extracted condition, but adjudication bound wrong candidate or failed
    channel_recalls = {"merged": ["f_info_fundscale", "f_wrong_scale"]}
    e2e = {
        "plan": {"conditions": [{"mention": "规模大于10亿"}]},
        "adjudication": {"condition_bindings": [{"condition_id": "c1", "candidate_key": "f_wrong_scale"}]},
        "dsl": None,
    }
    reason = classify_detailed_failure(case, channel_recalls, e2e)
    assert reason == ActionableReason.ADJUDICATION_ERROR


def test_failure_attribution_rerank_issue():
    case = GoldenCase.model_validate({
        "id": "test-rerank",
        "category": "metric",
        "query": "近三年收益",
        "expectedOutcome": "DSL",
        "expectedConditions": [
            ExpectedCondition(mention="近三年收益", domain="metric", relevantCandidateKeys=["f_return_3y"]),
        ],
    })
    # Channel had it in top 3, but merged pool dropped it
    channel_recalls = {
        "pgvector": ["f_return_3y", "f_other"],
        "lexical": [],
        "merged": ["f_other1", "f_other2", "f_other3"],
    }
    e2e = {"plan": {"conditions": [{"mention": "近三年收益"}]}, "dsl": None}
    reason = classify_detailed_failure(case, channel_recalls, e2e)
    assert reason == ActionableReason.RERANK_ISSUE


def test_evaluator_evaluate_case_mock_pipeline():
    case = GoldenCase.model_validate({
        "id": "eval-1",
        "category": "metric",
        "query": "最大回撤小于10%",
        "expectedOutcome": "DSL",
        "expectedConditions": [
            ExpectedCondition(
                mention="最大回撤小于10%",
                domain="metric",
                relevantCandidateKeys=["f_risk_maxdownside"],
                primaryCandidateKey="f_risk_maxdownside",
            )
        ],
        "expectedDsl": {
            "operator": "AND",
            "children": [
                {"metric": {"children": [{"metric": "f_risk_maxdownside", "operator": "<", "value": 0.1}]}}
            ],
        },
    })

    class MockPipeline:
        async def run(self, query: str):
            return {
                "channel_recalls": {
                    "pgvector": ["f_risk_maxdownside"],
                    "lexical": ["f_risk_maxdownside"],
                    "merged": ["f_risk_maxdownside"],
                },
                "plan": {"conditions": [{"mention": "最大回撤小于10%"}]},
                "adjudication": {"condition_bindings": [{"candidate_key": "f_risk_maxdownside"}]},
                "dsl": {
                    "operator": "AND",
                    "children": [
                        {"metric": {"children": [{"metric": "f_risk_maxdownside", "operator": "<", "value": 0.1}]}}
                    ],
                },
                "clarification": None,
            }

    evaluator = GoldenEvaluator(pipeline=MockPipeline())
    import asyncio
    res = asyncio.run(evaluator.evaluate_case(case))
    assert isinstance(res, CaseEvaluationResult)
    assert res.success is True
    assert res.channel_metrics["merged"]["hit@1"] == 1.0
    assert res.dsl_match["structural_match"] is True
    assert res.failure_reason is None


def test_evaluator_evaluate_dataset():
    case1 = GoldenCase.model_validate({
        "id": "eval-d1",
        "category": "metric",
        "query": "最大回撤小于10%",
        "expectedOutcome": "DSL",
        "expectedConditions": [
            ExpectedCondition(mention="最大回撤小于10%", domain="metric", relevantCandidateKeys=["f_dd"])
        ],
    })
    case2 = GoldenCase.model_validate({
        "id": "eval-d2",
        "category": "metric",
        "query": "未知查询",
        "expectedOutcome": "DSL",
        "expectedConditions": [
            ExpectedCondition(mention="未知", domain="metric", relevantCandidateKeys=["f_unknown"])
        ],
    })

    class MockPipeline:
        async def run(self, query: str):
            if "最大回撤" in query:
                return {
                    "channel_recalls": {"merged": ["f_dd"]},
                    "plan": {"conditions": [{"mention": "最大回撤小于10%"}]},
                    "dsl": {"children": [{"metric": {"children": [{"metric": "f_dd"}]}}]},
                }
            return {
                "channel_recalls": {"merged": []},
                "plan": {"conditions": []},
                "dsl": None,
            }

    evaluator = GoldenEvaluator(pipeline=MockPipeline())
    import asyncio
    ds_res = asyncio.run(evaluator.evaluate_dataset([case1, case2]))
    assert isinstance(ds_res, DatasetEvaluationResult)
    assert ds_res.total_cases == 2
    assert ds_res.passed_cases == 1
    assert ds_res.failed_cases == 1
    assert "merged" in ds_res.channel_summary
    assert len(ds_res.failures) == 1
