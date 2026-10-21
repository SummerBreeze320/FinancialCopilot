from __future__ import annotations

import pytest
from app.evaluation.metrics import (
    compute_clarification_match,
    compute_dsl_match,
    compute_ranking_metrics,
)


def test_ranking_metrics_calculation():
    recalled = ["k1", "k2", "k3", "k4", "k5"]
    relevant = ["k2", "k5"]
    primary = "k2"

    res = compute_ranking_metrics(recalled, relevant, primary, k_list=(1, 3, 5))
    # Hit@1 = 0, Hit@3 = 1 (k2 at rank 2), Hit@5 = 1
    assert res["hit@1"] == 0.0
    assert res["hit@3"] == 1.0
    assert res["hit@5"] == 1.0

    # Precision@3 = 1 / 3, Recall@3 = 1 / 2
    assert pytest.approx(res["precision@3"], 0.01) == 0.333
    assert res["recall@3"] == 0.5

    # MRR: first relevant is k2 at rank 2 -> 1/2 = 0.5
    assert res["mrr"] == 0.5

    # nDCG@3: DCG has k2 (relevance 2) at position 2 -> 2 / log2(3)
    assert res["ndcg@3"] > 0.0
    assert res["ndcg@3"] <= 1.0


def test_ranking_metrics_empty_inputs():
    res = compute_ranking_metrics([], ["k1"], "k1", k_list=(1, 3))
    assert res["hit@1"] == 0.0
    assert res["recall@1"] == 0.0
    assert res["mrr"] == 0.0
    assert res["ndcg@1"] == 0.0

    res_no_rel = compute_ranking_metrics(["k1"], [], None, k_list=(1, 3))
    assert res_no_rel["precision@1"] == 0.0
    assert res_no_rel["recall@1"] == 1.0
    assert res_no_rel["hit@1"] == 0.0


def test_dsl_structural_match():
    expected = {
        "version": "fund-screening/v1",
        "operator": "AND",
        "children": [
            {
                "sector": {"children": [{"sectorId": "100"}]},
                "metric": {"children": [{"metric": "f_scale", "operator": ">", "value": 10.0}]},
            }
        ],
        "limit": 20,
    }
    actual = {
        "version": "fund-screening/v1",
        "operator": "AND",
        "children": [
            {
                "sector": {"children": [{"sectorId": "100"}]},
                "metric": {"children": [{"metric": "f_scale", "operator": ">", "value": 10.0, "unit": "BILLION"}]},
            }
        ],
        "limit": 20,
    }
    match = compute_dsl_match(actual, expected)
    assert match["structural_match"] is True
    assert match["metrics_match"] is True
    assert match["sectors_match"] is True
    assert match["limit_match"] is True


def test_dsl_mismatch_detected():
    expected = {
        "operator": "AND",
        "children": [
            {"metric": {"children": [{"metric": "f_return_1y", "operator": ">", "value": 0.1}]}}
        ],
    }
    actual = {
        "operator": "OR",
        "children": [
            {"metric": {"children": [{"metric": "f_return_3y", "operator": ">", "value": 0.3}]}}
        ],
    }
    match = compute_dsl_match(actual, expected)
    assert match["structural_match"] is False
    assert match["metrics_match"] is False


def test_clarification_match():
    expected = {"code": "AMBIGUOUS_METRIC", "parameter": None}
    actual = {"code": "AMBIGUOUS_METRIC", "parameter": None, "options": ["收益率", "规模"]}
    res = compute_clarification_match(actual, expected)
    assert res["matched"] is True
    assert res["code_matched"] is True

    mismatch = compute_clarification_match({"code": "UNKNOWN_ENTITY"}, expected)
    assert mismatch["matched"] is False


def test_compute_target_accuracy_exact_match():
    from app.evaluation.metrics import compute_target_accuracy
    from app.evaluation.schema import ExpectedTarget

    actual_dsl = {
        "version": "fund-screening/v1",
        "operator": "AND",
        "limit": 20,
        "sort": [{"metric": "fund_code", "direction": "ASC"}],
        "children": [
            {
                "limit": 20,
                "sort": [{"metric": "fund_code", "direction": "ASC"}],
                "metric": {
                    "operator": "AND",
                    "children": [
                        {
                            "metric": "f_nav_adjustedreturn",
                            "operator": ">",
                            "value": 10.0,
                            "unit": "%",
                            "params": [
                                {"key": "startDate", "value": "20260701"},
                                {"key": "endDate", "value": "20260930"},
                            ],
                        }
                    ],
                },
            }
        ],
    }
    expected = ExpectedTarget(
        metric="f_nav_adjustedreturn",
        operator=">",
        value=10.0,
        unit="%",
        params={"startDate": "20260701", "endDate": "20260930"},
    )
    acc = compute_target_accuracy(actual_dsl, expected)
    assert acc["candidate_selection_accuracy"] == 1.0
    assert acc["operator_value_unit_accuracy"] == 1.0
    assert acc["parameter_accuracy"] == 1.0
    assert acc["executable_dsl_rate"] == 1.0


def test_compute_target_accuracy_param_mismatch():
    from app.evaluation.metrics import compute_target_accuracy
    from app.evaluation.schema import ExpectedTarget

    actual_dsl = {
        "version": "fund-screening/v1",
        "operator": "AND",
        "limit": 20,
        "sort": [{"metric": "fund_code", "direction": "ASC"}],
        "children": [
            {
                "limit": 20,
                "sort": [{"metric": "fund_code", "direction": "ASC"}],
                "metric": {
                    "operator": "AND",
                    "children": [
                        {
                            "metric": "f_nav_adjustedreturn",
                            "operator": ">",
                            "value": 10.0,
                            "unit": "%",
                            "params": [
                                {"key": "startDate", "value": "20260701"},
                                {"key": "endDate", "value": "20260830"},
                            ],
                        }
                    ],
                },
            }
        ],
    }
    expected = ExpectedTarget(
        metric="f_nav_adjustedreturn",
        operator=">",
        value=10.0,
        unit="%",
        params={"startDate": "20260701", "endDate": "20260930"},
    )
    acc = compute_target_accuracy(actual_dsl, expected)
    assert acc["candidate_selection_accuracy"] == 1.0
    assert acc["operator_value_unit_accuracy"] == 1.0
    assert acc["parameter_accuracy"] == 0.0


def test_golden_case_schema_supports_expected_target():
    from app.evaluation.schema import GoldenCase

    case = GoldenCase.model_validate({
        "id": "case-1",
        "category": "scenario",
        "query": "筛选季度收益大于10%的基金",
        "asOfDate": "20260930",
        "expected": {
            "metric": "f_nav_adjustedreturn",
            "operator": ">",
            "value": 10.0,
            "unit": "%",
            "params": {"startDate": "20260701", "endDate": "20260930"},
        },
    })
    assert case.as_of_date == "20260930"
    assert case.expected.metric == "f_nav_adjustedreturn"
    assert case.expected.params["startDate"] == "20260701"
