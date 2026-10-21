from __future__ import annotations

import argparse
import asyncio
import json
import math
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx

from app.config import Settings
from app.evaluation.loader import load_golden_dataset
from app.evaluation.metrics import compute_ranking_metrics
from app.evaluation.schema import GoldenCase
from app.retrieval.clients import OpenAiEmbeddingClient
from app.retrieval.pgvector import (
    LazyAsyncpgPool,
    PgVectorMetricRetriever,
    PgVectorScopeRetriever,
    PgVectorSearchClient,
)


class RagRetrievalEvaluator:
    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or Settings()
        self.embedding_http = httpx.AsyncClient(
            base_url=self.settings.embedding_base_url,
            headers={"Authorization": f"Bearer {self.settings.embedding_api_key}"},
            timeout=30,
            trust_env=False,
        )
        self.embedding = OpenAiEmbeddingClient(self.embedding_http, self.settings.embedding_model)
        self.pool = LazyAsyncpgPool(self.settings.database_url)
        self.client = PgVectorSearchClient(self.pool, self.embedding)
        self.metric_retriever = PgVectorMetricRetriever(self.client)
        self.scope_retriever = PgVectorScopeRetriever(self.client)

    async def close(self) -> None:
        await self.embedding_http.aclose()
        if self.pool._pool is not None:
            await self.pool._pool.close()

    async def evaluate_cases(
        self,
        cases: list[GoldenCase],
        domain: str = "metric",
        top_k: int = 10,
    ) -> dict[str, Any]:
        """评估指定 case 列表在对应领域的真实 pgvector 检索指标。"""
        retriever = self.metric_retriever if domain == "metric" else self.scope_retriever

        case_results: list[dict[str, Any]] = []
        latencies: list[float] = []

        for case in cases:
            # 确定检索文本：优先使用 condition mention，次选 case.query
            target_keys: list[str] = []
            primary_key: str | None = None
            query_text = case.query

            for cond in case.expected_conditions:
                if cond.domain == domain or domain == "all":
                    target_keys.extend(cond.relevant_candidate_keys)
                    if not primary_key and cond.primary_candidate_key:
                        primary_key = cond.primary_candidate_key
                    if cond.mention and len(case.expected_conditions) == 1:
                        query_text = cond.mention

            target_keys = list(dict.fromkeys(target_keys))
            if not target_keys:
                continue

            t0 = time.perf_counter()
            candidates = await retriever.search(query_text, top_k)
            latency_ms = (time.perf_counter() - t0) * 1000.0
            latencies.append(latency_ms)

            recalled_keys = [c.key for c in candidates]
            metrics = compute_ranking_metrics(
                recalled_keys=recalled_keys,
                relevant_keys=target_keys,
                primary_key=primary_key,
                k_list=(1, 3, 5, 10),
            )

            is_hit = metrics.get("hit@10", 0.0) > 0
            case_results.append({
                "case_id": case.id,
                "query": case.query,
                "domain": domain,
                "target_keys": target_keys,
                "primary_key": primary_key,
                "recalled_keys": recalled_keys,
                "recalled_names": [c.name for c in candidates],
                "scores": [c.score for c in candidates],
                "metrics": metrics,
                "latency_ms": latency_ms,
                "is_hit": is_hit,
            })

        total = len(case_results)
        if total == 0:
            return {"total": 0}

        avg_metrics: dict[str, float] = {}
        for m_name in ("hit@1", "hit@3", "hit@5", "hit@10", "recall@1", "recall@3", "recall@5", "recall@10", "mrr", "ndcg@5", "ndcg@10"):
            avg_metrics[m_name] = sum(r["metrics"].get(m_name, 0.0) for r in case_results) / total

        bad_cases = [r for r in case_results if not r["is_hit"]]
        near_misses = [r for r in case_results if r["is_hit"] and r["metrics"].get("hit@1", 0.0) == 0.0]

        return {
            "domain": domain,
            "total_cases": total,
            "avg_latency_ms": sum(latencies) / len(latencies) if latencies else 0.0,
            "p95_latency_ms": sorted(latencies)[int(len(latencies) * 0.95)] if latencies else 0.0,
            "summary_metrics": avg_metrics,
            "bad_cases_count": len(bad_cases),
            "bad_cases": bad_cases[:10],
            "near_misses_count": len(near_misses),
            "near_misses": near_misses[:5],
            "all_results": case_results,
        }


def render_rag_markdown_report(reports_by_domain: dict[str, Any], output_path: Path) -> None:
    lines = [
        "# 基金筛选 RAG 真实 pgvector 向量召回评测报告",
        "",
        f"- **评测生成时间**: {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}",
        "- **向量数据库**: PostgreSQL 16 + pgvector (1024 维 HNSW 余弦索引)",
        "- **向量模型**: Ollama `qwen3-embedding:0.6b` (1024 维)",
        "",
        "## 1. 核心指标汇总表",
        "",
        "| 评测领域 | 样本总量 | Hit@1 | Hit@3 | Hit@5 | Hit@10 | MRR | NDCG@10 | 平均耗时 (ms) |",
        "| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |",
    ]

    for domain_name, data in reports_by_domain.items():
        if not data or data.get("total_cases", 0) == 0:
            continue
        m = data["summary_metrics"]
        lines.append(
            f"| **{domain_name}** | {data['total_cases']} | "
            f"{m['hit@1']*100:.1f}% | {m['hit@3']*100:.1f}% | {m['hit@5']*100:.1f}% | {m['hit@10']*100:.1f}% | "
            f"{m['mrr']:.3f} | {m['ndcg@10']:.3f} | {data['avg_latency_ms']:.1f}ms |"
        )

    lines.append("")
    lines.append("## 2. 评测分领域详细表现")
    lines.append("")

    for domain_name, data in reports_by_domain.items():
        if not data or data.get("total_cases", 0) == 0:
            continue
        m = data["summary_metrics"]
        lines.extend([
            f"### 2.{domain_name} 召回分析",
            f"- **样本数**: {data['total_cases']}",
            f"- **平均延迟**: {data['avg_latency_ms']:.2f}ms (P95: {data['p95_latency_ms']:.2f}ms)",
            f"- **Hit@1 (Top-1 命中率)**: {m['hit@1']*100:.2f}%",
            f"- **Hit@3 (Top-3 命中率)**: {m['hit@3']*100:.2f}%",
            f"- **Hit@5 (Top-5 命中率)**: {m['hit@5']*100:.2f}%",
            f"- **Hit@10 (Top-10 召回率)**: {m['hit@10']*100:.2f}%",
            f"- **MRR**: {m['mrr']:.4f}",
            f"- **NDCG@10**: {m['ndcg@10']:.4f}",
            f"- **未命中数量 (Bad Cases)**: {data['bad_cases_count']}",
            "",
        ])

        if data.get("bad_cases"):
            lines.append("#### 未命中典型案例 (Bad Cases)")
            lines.append("| Case ID | Query | 期望指标/板块 | 实际 Top-3 召回结果 |")
            lines.append("| :--- | :--- | :--- | :--- |")
            for bc in data["bad_cases"]:
                top3_str = ", ".join(f"{n}({bc['recalled_keys'][i]}:{bc['scores'][i]:.2f})" for i, n in enumerate(bc["recalled_names"][:3]))
                lines.append(f"| `{bc['case_id']}` | {bc['query']} | `{','.join(bc['target_keys'])}` | {top3_str} |")
            lines.append("")

    output_path.write_text("\n".join(lines), encoding="utf-8")


async def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate Real PGVector RAG Retrieval on Golden Datasets")
    parser.add_argument("--dataset-dir", type=Path, default=Path(__file__).resolve().parents[2] / "data" / "golden")
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parents[2] / "data" / "golden" / "reports")
    parser.add_argument("--top-k", type=int, default=10)
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    evaluator = RagRetrievalEvaluator()

    try:
        print("==================================================================")
        print("          Starting Real PGVector RAG Retrieval Evaluation        ")
        print("==================================================================")

        reports_by_domain: dict[str, Any] = {}

        # 1. 评估指标集
        metric_file = args.dataset_dir / "metrics.jsonl"
        if metric_file.exists():
            print(f"--> Loading metric cases from {metric_file.name}...")
            metric_cases = load_golden_dataset(metric_file)
            print(f"--> Evaluating {len(metric_cases)} metric cases against pgvector 'rag_fund_metric'...")
            reports_by_domain["指标体系 (Metrics)"] = await evaluator.evaluate_cases(metric_cases, domain="metric", top_k=args.top_k)

        # 2. 评估板块集
        sector_file = args.dataset_dir / "sectors-top100.jsonl"
        if sector_file.exists():
            print(f"--> Loading sector cases from {sector_file.name}...")
            sector_cases = load_golden_dataset(sector_file)
            print(f"--> Evaluating {len(sector_cases)} sector cases against pgvector 'rag_fund_sector'...")
            reports_by_domain["板块体系 (Sectors)"] = await evaluator.evaluate_cases(sector_cases, domain="scope", top_k=args.top_k)

        # 3. 评估复合场景集中的指标召回
        composite_file = args.dataset_dir / "composite-research-scenarios.jsonl"
        if composite_file.exists():
            print(f"--> Loading composite scenario cases from {composite_file.name}...")
            comp_cases = load_golden_dataset(composite_file)
            print(f"--> Evaluating {len(comp_cases)} composite scenarios against pgvector...")
            reports_by_domain["复合研究场景 (Composite)"] = await evaluator.evaluate_cases(comp_cases, domain="metric", top_k=args.top_k)

        # 渲染与保存报告
        json_report_path = args.output_dir / "rag_retrieval_report.json"
        md_report_path = args.output_dir / "rag_retrieval_report.md"

        # 过滤掉大字段以保存轻量 JSON
        lightweight_report = {}
        for d, rep in reports_by_domain.items():
            rep_copy = dict(rep)
            rep_copy.pop("all_results", None)
            lightweight_report[d] = rep_copy

        json_report_path.write_text(json.dumps(lightweight_report, ensure_ascii=False, indent=2), encoding="utf-8")
        render_rag_markdown_report(reports_by_domain, md_report_path)

        print("\n========================= RAG Evaluation Summary =========================")
        for domain_name, data in reports_by_domain.items():
            if not data or data.get("total_cases", 0) == 0:
                continue
            m = data["summary_metrics"]
            print(f"\n[{domain_name}] ({data['total_cases']} cases, Avg Latency: {data['avg_latency_ms']:.1f}ms)")
            print(f"  Hit@1:  {m['hit@1']*100:6.2f}% | Hit@3:  {m['hit@3']*100:6.2f}% | Hit@5:  {m['hit@5']*100:6.2f}% | Hit@10: {m['hit@10']*100:6.2f}%")
            print(f"  MRR:    {m['mrr']:6.4f}  | NDCG@5: {m['ndcg@5']:6.4f}  | NDCG@10:{m['ndcg@10']:6.4f}")
            print(f"  Bad Cases: {data['bad_cases_count']} / {data['total_cases']}")
        print(f"\n[OK] Reports saved to:")
        print(f"  - Markdown: {md_report_path}")
        print(f"  - JSON:     {json_report_path}")
        print("==========================================================================\n")

    finally:
        await evaluator.close()


if __name__ == "__main__":
    asyncio.run(main())
