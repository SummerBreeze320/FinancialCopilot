"""单元测试：RAG 评估器（检索指标、LLM Judge 裁判、人工评审与切片质检）。"""

import pytest
import os
from app.diagnostics.evaluator import (
    ChunkValidator,
    ChunkVerificationItem,
    ChunkVerificationStore,
    HumanReviewItem,
    HumanReviewStore,
    LlmJudgeEvaluator,
    calculate_hit_at_k,
    calculate_mrr,
    calculate_ndcg_at_k,
    calculate_precision_at_k,
    calculate_recall_at_k,
)


def test_retrieval_evaluation_metrics():
    # 模拟真值切片集合
    ground_truth = ["chunk_01", "chunk_02"]

    # 1. 完全命中（Top-2 即包含全部真值）
    preds_perfect = ["chunk_01", "chunk_02", "chunk_03", "chunk_04", "chunk_05"]
    assert calculate_hit_at_k(preds_perfect, ground_truth, k=5) == 1.0
    assert calculate_recall_at_k(preds_perfect, ground_truth, k=5) == 1.0
    # Precision@5 = 2 / 5 = 0.40
    assert calculate_precision_at_k(preds_perfect, ground_truth, k=5) == 0.40
    # Precision@2 = 2 / 2 = 1.00
    assert calculate_precision_at_k(preds_perfect, ground_truth, k=2) == 1.00
    assert calculate_mrr(preds_perfect, ground_truth) == 1.0
    assert calculate_ndcg_at_k(preds_perfect, ground_truth, k=5) > 0.9

    # 2. 部分命中（Top-5 只包含 1 个真值，排在第 3 位）
    preds_partial = ["other_1", "other_2", "chunk_01", "other_3", "other_4"]
    assert calculate_hit_at_k(preds_partial, ground_truth, k=5) == 1.0
    # 命中 1/2 = 0.5
    assert calculate_recall_at_k(preds_partial, ground_truth, k=5) == 0.5
    # Precision@5 = 1 / 5 = 0.20
    assert calculate_precision_at_k(preds_partial, ground_truth, k=5) == 0.20
    assert calculate_mrr(preds_partial, ground_truth) == round(1.0 / 3, 4)

    # 3. 完全未命中
    preds_none = ["other_1", "other_2", "other_3"]
    assert calculate_hit_at_k(preds_none, ground_truth, k=5) == 0.0
    assert calculate_recall_at_k(preds_none, ground_truth, k=5) == 0.0
    assert calculate_precision_at_k(preds_none, ground_truth, k=5) == 0.0
    assert calculate_mrr(preds_none, ground_truth) == 0.0


@pytest.mark.asyncio
async def test_llm_judge_evaluator_rule_fallback():
    judge = LlmJudgeEvaluator(base_url="http://127.0.0.1:9999/v1")  # 故意给定不可用端口以触发高质量规则裁判
    query = "诺安成长2026Q2的基金份额是多少？"
    context_chunks = [
        {
            "id": "c1",
            "doc_title": "诺安成长2026年第2季度报告",
            "page_number": 2,
            "content": "报告期末基金份额总额为 14,561,421,790.31 份，运作方式为契约型开放式。",
        }
    ]
    # 正确答案
    answer_correct = "根据报告，诺安成长2026年第2季度报告期末基金份额总额为 14,561,421,790.31 份。"
    result = await judge.evaluate_generation(query, answer_correct, context_chunks)
    assert result.correctness_score == 5
    assert "完全正确" in result.correctness_label
    assert result.no_hallucination is True

    # 错误/捏造数值答案 (幻觉)
    answer_hallucinated = "诺安成长2026年第2季度基金份额为 99,888,777,666 份，暴增 500%！"
    result_bad = await judge.evaluate_generation(query, answer_hallucinated, context_chunks)
    assert result_bad.correctness_score == 1
    assert result_bad.no_hallucination is False


def test_chunk_validator_and_integrity():
    validator = ChunkValidator()

    # 1. 规范表格切片（带表头挂顶）
    valid_table_chunk = {
        "id": "c_table",
        "content": "| 序号 | 股票代码 | 股票名称 | 公允价值(元) |\n| --- | --- | --- | --- |\n| 1 | 002371 | 北方华创 | 1,234,567.89 |\n| 2 | 688012 | 中微公司 | 987,654.32 |",
        "has_table": True,
        "metadata": {"has_table": True, "stock_codes": ["002371", "688012"]},
    }
    report = validator.inspect_chunk_quality(valid_table_chunk)
    assert report["has_table"] is True
    assert report["header_verified"] is True
    assert report["cell_tear_detected"] is False
    assert report["has_entities"] is True
    assert report["quality_score"] >= 80

    # 2. 撕裂表格切片（首行不是表头）
    broken_table_chunk = {
        "id": "c_broken",
        "content": "| 1 | 002371 | 北方华创 | 1,234,567.89 |\n| 2 | 688012 | 中微公司 | 987,654.32 |",
        "has_table": True,
        "metadata": {"has_table": True},
    }
    report_broken = validator.inspect_chunk_quality(broken_table_chunk)
    assert report_broken["header_verified"] is False
    assert "表格表头未成功挂顶" in report_broken["quality_issues"]


def test_verification_and_human_review_stores(tmp_path):
    verify_file = str(tmp_path / "test_verify.json")
    review_file = str(tmp_path / "test_review.json")

    v_store = ChunkVerificationStore(file_path=verify_file)
    v_item = ChunkVerificationItem(chunk_id="chunk_001", status="VERIFIED", notes="表格表头完整无误")
    v_store.save_verification(v_item)
    status = v_store.get_status("chunk_001")
    assert status["status"] == "VERIFIED"
    assert status["notes"] == "表格表头完整无误"

    h_store = HumanReviewStore(file_path=review_file)
    h_item = HumanReviewItem(
        review_id="rev_001",
        query="拓荆科技估值调整依据",
        answer="采用指数收益法进行调整",
        fact_correctness=5,
        completeness=5,
        conciseness=5,
        no_hallucination=True,
    )
    h_store.save_review(h_item)
    summary = h_store.get_summary_stats()
    assert summary["total_reviews"] == 1
    assert summary["avg_fact_correctness"] == 5.0
    assert summary["no_hallucination_rate"] == 1.0
