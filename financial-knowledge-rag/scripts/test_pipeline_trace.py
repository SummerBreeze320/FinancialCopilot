import httpx
import sys

sys.path.insert(0, ".")
sys.stdout.reconfigure(encoding="utf-8")

client = httpx.Client(trust_env=False, timeout=60.0)
payload = {
    "query": "对比诺安成长2026年第1季度和第2季度的报告期末基金份额总额是多少？前三大重仓股票有何变动？",
    "document_id": "all",
    "top_k": 5
}
r = client.post("http://127.0.0.1:8820/api/knowledge/pipeline/trace", json=payload)
print("Pipeline Trace HTTP Status:", r.status_code)
if r.status_code == 200:
    d = r.json()
    pipe = d.get("pipeline", {})
    print("✓ Stage 1:", pipe.get("stage_1_parsing", {}).get("stage_name"))
    print("✓ Stage 2 Total Chunks:", pipe.get("stage_2_chunking", {}).get("statistics", {}).get("total_chunks"))
    print("✓ Stage 4 Recalled:", pipe.get("stage_4_hybrid_retrieval", {}).get("funnel_metrics"))
    print("✓ Stage 5 RRF Fused Top 1:", pipe.get("stage_5_rrf_fusion", {}).get("fused_ranking", [{}])[0].get("chunk_id"))
    st6_candidates = pipe.get("stage_6_neural_rerank", {}).get("scored_candidates", [])
    if st6_candidates:
        print("✓ Stage 6 Neural Top 1:", st6_candidates[0].get("chunk_id"), "Base Score:", st6_candidates[0].get("base_neural_score"), "Final:", st6_candidates[0].get("final_score"))
    ans = pipe.get("stage_7_generation_audit", {}).get("answer_text", "")
    print(f"✓ Stage 7 Answer (len={len(ans)}):\n", ans[:250], "...")
else:
    print("Error:", r.text[:500])
