import sys
sys.stdout.reconfigure(encoding='utf-8')
import httpx

q = '基金在2026年第2季度末的前三大重仓股票分别是什么？公允价值和占基金资产净值比例是多少？'
r = httpx.post('http://127.0.0.1:8820/api/knowledge/pipeline/trace', json={'query': q}, trust_env=False, timeout=60).json()
p = r['pipeline']
s1 = p['stage_1_parsing']
s2 = p['stage_2_chunking']
s7 = p['stage_7_generation_audit']

print("=== Stage 1 Metrics ===")
print("Tables:", s1['total_tables_detected'], "Charts:", s1.get('total_charts_detected'))
print("Header/Footer Cleaned:", s1.get('header_footer_cleaned'))
print("Stitching:", s1.get('cross_page_stitching'))

print("\n=== Stage 2 Metrics ===")
print("Total Chunks:", s2['statistics']['total_chunks'])
print("Table Chunks:", s2['statistics']['table_chunks_count'])
print("Chart Chunks:", s2['statistics'].get('chart_chunks_count'))
print("Text Chunks:", s2['statistics']['text_chunks_count'])
print("Splitter Principles count:", len(s2['strategy'].get('principles', [])))

print("\n=== Stage 7 Output ===")
print(s7['answer_text'])
print("\nFaithfulness Score:", s7['faithfulness_score'])
for idx, cl in enumerate(s7['claims_audit'], 1):
    q_str = cl.get('exact_quote') or ''
    print(f" Claim {idx}: {cl['claim_text']} -> {cl['is_faithful']} | Quote: {repr(q_str[:45])}")
