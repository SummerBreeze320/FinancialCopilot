import requests

url = "http://127.0.0.1:8820/api/knowledge/pipeline/trace"
payload = {
    "query": "诺安成长2026年第1季度和第2季度的期末基金份额总额以及前三大重仓股票及变动",
    "top_k": 5,
    "score_threshold": 0.35
}

resp = requests.post(url, json=payload, timeout=60)
data = resp.json()

pipe = data.get("pipeline", {})
s6 = pipe.get("stage_6_neural_rerank", {})
print("Table Intent Detected:", s6.get("table_intent_detected"))
print("\nSelected Chunks for Generation:")
for c in s6.get("scored_candidates", []):
    if c.get("is_selected"):
        print(f"  [#{c.get('final_rank')}] P.{c.get('page_number')} Table={c.get('has_table')} Score={c.get('final_score')} | {c.get('section_title')}")

s7 = pipe.get("stage_7_generation_audit", {})
print("\nLLM Legal Answer:\n", s7.get("answer_text"))

print("\nAuditor Results:")
print("Faithfulness Score:", s7.get("faithfulness_score"))
for a in s7.get("claims_audit", []):
    print(f"  - [{a.get('is_faithful')}] {a.get('statement')}")
