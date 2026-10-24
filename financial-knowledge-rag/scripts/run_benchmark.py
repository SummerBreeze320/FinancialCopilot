"""离线超参数网格搜索实验脚本与金融黄金评测集评估器。"""

import argparse
import asyncio
import json
import math
import os
from typing import Any, Dict, List, Optional
import httpx


def calculate_hit_at_k(
    retrieved_ids: List[str],
    ground_truth_ids: List[str],
    k: int = 5,
) -> float:
    """计算 Top-K 命中率 (Hit@K)。

    :param retrieved_ids: 检索器返回的切片 ID 列表（按排序优先级降序）
    :param ground_truth_ids: 黄金标准真值切片 ID 列表
    :param k: 判定窗口截断大小
    :return: 命中返回 1.0，否则返回 0.0
    """
    top_k_items = set(retrieved_ids[:k])
    gt_set = set(ground_truth_ids)
    return 1.0 if bool(top_k_items & gt_set) else 0.0


def calculate_mrr(
    retrieved_ids: List[str],
    ground_truth_ids: List[str],
) -> float:
    """计算平均倒数排名 (Mean Reciprocal Rank, MRR)。

    :return: 首个命中真值切片的倒数排名 (1/rank)；若全部未命中则返回 0.0
    """
    gt_set = set(ground_truth_ids)
    for rank, item_id in enumerate(retrieved_ids, start=1):
        if item_id in gt_set:
            return round(1.0 / rank, 4)
    return 0.0


def calculate_ndcg_at_k(
    retrieved_ids: List[str],
    ground_truth_ids: List[str],
    k: int = 5,
) -> float:
    """计算归一化折损累计增益 (NDCG@K)。"""
    gt_set = set(ground_truth_ids)
    dcg = 0.0
    sub_preds = retrieved_ids[:k]

    for rank, item_id in enumerate(sub_preds, start=1):
        rel = 1.0 if item_id in gt_set else 0.0
        if rel > 0:
            dcg += rel / math.log2(rank + 1)

    # 理想 IDCG
    ideal_count = min(k, len(ground_truth_ids))
    if ideal_count == 0:
        return 0.0

    idcg = sum(1.0 / math.log2(r + 1) for r in range(1, ideal_count + 1))
    return round(dcg / idcg, 4) if idcg > 0 else 0.0


def evaluate_dataset(
    dataset: List[Dict[str, Any]],
    predictions: Dict[str, List[str]],
    k: int = 5,
) -> Dict[str, float]:
    """批量评估黄金数据集上的召回排序性能指标。

    :param dataset: 评测样本列表，每个样本包含 query 与 ground_truth_chunks
    :param predictions: 字典，映射 query -> 检索返回的 chunk_id 列表
    :param k: 截断评估窗口
    :return: 包含 hit_at_k, mrr, ndcg_at_k 平均分数的字典
    """
    if not dataset:
        return {"hit_at_k": 0.0, "mrr": 0.0, "ndcg_at_k": 0.0}

    hits: List[float] = []
    mrrs: List[float] = []
    ndcgs: List[float] = []

    for item in dataset:
        q = item.get("query", "")
        gt = item.get("ground_truth_chunks", [])
        preds = predictions.get(q, [])

        hits.append(calculate_hit_at_k(preds, gt, k=k))
        mrrs.append(calculate_mrr(preds, gt))
        ndcgs.append(calculate_ndcg_at_k(preds, gt, k=k))

    total = float(len(dataset))
    return {
        "hit_at_k": round(sum(hits) / total, 4),
        "mrr": round(sum(mrrs) / total, 4),
        "ndcg_at_k": round(sum(ndcgs) / total, 4),
    }


def load_golden_dataset(path: str) -> List[Dict[str, Any]]:
    """从 JSONL 文件中读取黄金评测问答集。"""
    records: List[Dict[str, Any]] = []
    if not os.path.exists(path):
        return records
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    return records


async def run_grid_search(
    api_url: str = "http://127.0.0.1:8820/api/knowledge/retrieve",
    dataset_path: str = "data/golden/financial_qa_eval.jsonl",
) -> None:
    """离线超参数网格搜索实验入口。"""
    dataset = load_golden_dataset(dataset_path)
    print(f"[BENCHMARK] 成功加载黄金评测集，共 {len(dataset)} 条样本。")

    param_grid = [
        {"top_k": 5, "score_threshold": 0.25, "rerank_strategy": "neural"},
        {"top_k": 5, "score_threshold": 0.35, "rerank_strategy": "neural"},
        {"top_k": 5, "score_threshold": 0.35, "rerank_strategy": "rrf"},
    ]

    print("\n" + "=" * 70)
    print(f"{'策略配置':<30} | {'Hit@5':<8} | {'MRR':<8} | {'NDCG@5':<8}")
    print("-" * 70)

    async with httpx.AsyncClient(timeout=30.0) as client:
        for params in param_grid:
            predictions: Dict[str, List[str]] = {}
            for item in dataset:
                q = item["query"]
                payload = {
                    "query": q,
                    "top_k": params["top_k"],
                    "score_threshold": params["score_threshold"],
                    "rerank_strategy": params["rerank_strategy"],
                    "filters": item.get("filters"),
                }
                try:
                    resp = await client.post(api_url, json=payload)
                    if resp.status_code == 200:
                        chunks = resp.json().get("chunks", [])
                        predictions[q] = [c["chunk_id"] for c in chunks]
                    else:
                        predictions[q] = []
                except Exception:
                    predictions[q] = []

            metrics = evaluate_dataset(dataset, predictions, k=params["top_k"])
            config_desc = f"{params['rerank_strategy']}-th{params['score_threshold']}"
            print(
                f"{config_desc:<30} | "
                f"{metrics['hit_at_k']:<8.4f} | "
                f"{metrics['mrr']:<8.4f} | "
                f"{metrics['ndcg_at_k']:<8.4f}"
            )

    print("=" * 70)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="金融知识库 RAG 网格搜索与离线评测")
    parser.add_argument(
        "--dataset",
        default="data/golden/financial_qa_eval.jsonl",
        help="评测集路径",
    )
    parser.add_argument(
        "--url",
        default="http://127.0.0.1:8820/api/knowledge/retrieve",
        help="检索服务接口路径",
    )
    args = parser.parse_args()
    asyncio.run(run_grid_search(api_url=args.url, dataset_path=args.dataset))
