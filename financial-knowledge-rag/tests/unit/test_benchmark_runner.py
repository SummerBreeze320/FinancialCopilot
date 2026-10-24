"""单元测试：离线评测指标计算与网格搜索套件。"""

import pytest
from scripts.run_benchmark import (
    calculate_hit_at_k,
    calculate_mrr,
    calculate_ndcg_at_k,
    evaluate_dataset,
)


def test_ranking_metrics_calculation():
    ground_truth = ["c_target_1"]

    # 1. 第一名命中
    preds_rank1 = ["c_target_1", "c_other", "c_other2"]
    assert calculate_hit_at_k(preds_rank1, ground_truth, k=1) == 1.0
    assert calculate_hit_at_k(preds_rank1, ground_truth, k=5) == 1.0
    assert calculate_mrr(preds_rank1, ground_truth) == 1.0
    assert pytest.approx(calculate_ndcg_at_k(preds_rank1, ground_truth, k=3), 0.01) == 1.0

    # 2. 第二名命中
    preds_rank2 = ["c_other", "c_target_1", "c_other2"]
    assert calculate_hit_at_k(preds_rank2, ground_truth, k=1) == 0.0
    assert calculate_hit_at_k(preds_rank2, ground_truth, k=2) == 1.0
    assert calculate_mrr(preds_rank2, ground_truth) == 0.5
    # DCG = 1 / log2(2 + 1) = 1 / 1.58496 = 0.6309, IDCG = 1.0 => NDCG ≈ 0.6309
    assert calculate_ndcg_at_k(preds_rank2, ground_truth, k=3) < 1.0
    assert calculate_ndcg_at_k(preds_rank2, ground_truth, k=3) > 0.6


def test_evaluate_dataset_mock():
    dataset = [
        {
            "query": "宁德时代毛利率",
            "ground_truth_chunks": ["c1"],
        },
        {
            "query": "夏普比率计算公式",
            "ground_truth_chunks": ["c2"],
        },
    ]

    mock_results = {
        "宁德时代毛利率": ["c1", "c3", "c4"],
        "夏普比率计算公式": ["c5", "c2", "c6"],
    }

    metrics = evaluate_dataset(dataset, mock_results, k=5)
    assert metrics["hit_at_k"] == 1.0
    # MRR = (1.0 + 0.5) / 2 = 0.75
    assert pytest.approx(metrics["mrr"], 0.01) == 0.75
    assert metrics["ndcg_at_k"] > 0.8
