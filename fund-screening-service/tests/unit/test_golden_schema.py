import pytest
from app.evaluation.schema import GoldenCase, ExpectedCondition, validate_and_sanitize_regression_case
from app.evaluation.loader import load_golden_dataset


def test_golden_case_validation():
    case = GoldenCase.model_validate({
        "id": "metric-return-1y",
        "category": "metric",
        "query": "近1年收益率超过10%",
        "difficulty": "clear",
        "expectedOutcome": "DSL",
        "expectedConditions": [
            {
                "mention": "近1年收益率超过10%",
                "domain": "metric",
                "relevantCandidateKeys": ["f_nav_return_1y", "f_return_1y"],
                "primaryCandidateKey": "f_return_1y",
            }
        ],
        "expectedDsl": {"version": "fund-screening/v1"},
    })
    assert case.id == "metric-return-1y"
    assert case.expected_conditions[0].primary_candidate_key == "f_return_1y"
    assert len(case.expected_conditions[0].relevant_candidate_keys) == 2


def test_vague_query_clarification_expectation():
    case = GoldenCase.model_validate({
        "id": "vague-high-return",
        "category": "scenario",
        "query": "收益好的基金",
        "difficulty": "ambiguous",
        "expectedOutcome": "CLARIFICATION",
        "expectedConditions": [
            {
                "mention": "收益好的",
                "domain": "metric",
                "relevantCandidateKeys": ["f_return_1y", "f_return_3y"],
            }
        ],
        "expectedClarification": {"code": "AMBIGUOUS_METRIC"},
    })
    assert case.expected_outcome == "CLARIFICATION"
    assert case.expected_clarification["code"] == "AMBIGUOUS_METRIC"


def test_pii_sanitization_removes_sensitive_data():
    dirty_case = {
        "id": "user-fail-1",
        "category": "regression",
        "query": "帮张三(账号6222021234567890, 电话13800138000)筛选易方达蓝筹",
        "userId": "usr_998877",
        "conversationId": "conv_secret_123",
        "expectedOutcome": "DSL",
        "expectedConditions": [],
    }
    clean_case = validate_and_sanitize_regression_case(dirty_case)
    assert "userId" not in clean_case
    assert "conversationId" not in clean_case
    assert "6222021234567890" not in clean_case["query"]
    assert "13800138000" not in clean_case["query"]
    assert "[ACCOUNT]" in clean_case["query"]
    assert "[PHONE]" in clean_case["query"]
