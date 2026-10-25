"""验证金融知识库端到端检索与 LLM 生成审计效果。"""

import json
import sys
import httpx

sys.stdout.reconfigure(encoding="utf-8")


def test_queries():
    client = httpx.Client(trust_env=False, timeout=15)

    test_cases = [
        "诺安成长混合型基金 2026年第2季度 运作方式与份额总额是多少？",
        "诺安成长混合的投资策略和业绩比较基准是什么？",
        "诺安成长混合的基金管理人和托管人是谁？",
    ]

    for idx, query in enumerate(test_cases, 1):
        print(f"\n============================================================")
        print(f"【测试用例 {idx}】用户查询: {query}")
        print(f"============================================================")

        req_payload = {
            "query": query,
            "top_k": 3,
            "filters": {
                "cognitive_layers": ["GROUND_TRUTH"],
                "category_paths": ["GROUND_TRUTH.PERIODIC.FUND_PERIODIC"]
            }
        }

        resp = client.post("http://127.0.0.1:8820/api/knowledge/retrieve", json=req_payload)
        if resp.status_code != 200:
            print(f"[ERROR] 接口返回异常: {resp.status_code}, 内容: {resp.text}")
            continue

        data = resp.json()
        diag = data.get("diagnostics", {})
        print(f"★ 粗召回候选数: {data.get('total_candidates')} 个")
        print(f"★ 耗时追踪 (ms): 粗召回={diag.get('latency_trace', {}).get('hybrid_recall_latency', 0):.1f}ms | 重排={diag.get('latency_trace', {}).get('rerank_latency', 0):.1f}ms")

        chunks = data.get("chunks", [])
        for c_idx, c in enumerate(chunks, 1):
            table_flag = "【含Markdown表格】" if c.get("has_table") else "【纯文本】"
            print(f"\n  [Top {c_idx}] 得分: {c.get('score'):.4f} {table_flag} 来自: {c.get('document_title')} (P.{c.get('page_number')})")
            preview = c.get("content", "").replace("\n", " ")[:200]
            print(f"       内容摘要: {preview}...")


if __name__ == "__main__":
    test_queries()
