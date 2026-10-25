"""端到端验证：从知识库切片检索 -> 本地 Codex-Bridge 生成研报回答 -> 事实与忠诚性溯源审计。"""

import asyncio
import json
import sys
import httpx

from app.generator.provenance_auditor import ProvenanceAuditor

sys.stdout.reconfigure(encoding="utf-8")


async def run_e2e_qa(query: str):
    print(f"\n============================================================")
    print(f"【真实问答验证】用户问题: {query}")
    print(f"============================================================")

    # 1. 向 RAG 服务请求 Top-K 知识切片
    print("[1/3] 正在调用 RAG 混合检索接口获取 Top-3 切片...")
    async with httpx.AsyncClient(trust_env=False, timeout=15) as client:
        rag_resp = await client.post(
            "http://127.0.0.1:8820/api/knowledge/retrieve",
            json={
                "query": query,
                "top_k": 5,
                "filters": {"cognitive_layers": ["GROUND_TRUTH"]},
            },
        )
        rag_data = rag_resp.json()
        chunks = rag_data.get("chunks", [])
        print(f"      成功召回 {len(chunks)} 个切片:")
        for idx, c in enumerate(chunks, 1):
            table_flag = "[含Markdown表格]" if c.get("has_table") else "[纯文本]"
            print(f"       ({idx}) 得分: {c.get('score'):.4f} {table_flag} P.{c.get('page_number')}: {c.get('content')[:120].replace(chr(10), ' ')}...")

    # 2. 拼接 Context 并调用本地 Codex-Bridge (gemini-3.8-flash-low) 生成回答
    print("\n[2/3] 正在调用本地 Codex-Bridge (端口 9090) 生成基于切片的回答...")
    context_text = "\n\n".join([f"【参考切片 {idx+1}】(来自 {c.get('document_title')} P.{c.get('page_number')}):\n{c.get('content')}" for idx, c in enumerate(chunks)])

    prompt = f"""你是一名严谨的金融基金分析师。请依据提供的官方季报切片信息回答用户问题。
要求：
1. 必须严格忠实于参考切片，严禁编造数据或臆测；
2. 如果参考资料中有明确数据（如份额、基准、日期、公司），请准确引用并在回答中标注切片编号如 [1], [2]；
3. 输出简洁有条理的回答。

【参考资料】:
{context_text}

【用户问题】:
{query}
"""

    async with httpx.AsyncClient(trust_env=False, timeout=60) as client:
        llm_resp = await client.post(
            "http://127.0.0.1:9090/v1/chat/completions",
            json={
                "model": "gemini-3.8-flash-low",
                "messages": [{"role": "user", "content": prompt}],
            },
        )
        llm_json = llm_resp.json()
        answer = llm_json["choices"][0]["message"]["content"].strip()
        print(f"\n★ 本地 Codex-Bridge 模型回答:\n{answer}\n")

    # 3. 执行端到端细粒度断言溯源与幻觉审计
    print("[3/3] 正在执行 Claim-Level 事实性与忠诚性溯源审计...")
    auditor = ProvenanceAuditor()
    audit_res = auditor.audit_response(answer, chunks)

    print(f"★ 溯源审计结果:")
    print(f"   - 忠诚度得分 (Faithfulness Score): {audit_res.faithfulness_score:.2f}")
    print(f"   - 断言总数: {audit_res.total_claims} (忠实断言: {audit_res.faithful_claims_count})")
    print(f"   - 预警数: {len(audit_res.warnings)}")
    for cit in audit_res.claims[:5]:
        flag = "✓ 忠实" if cit.is_faithful else "✗ 幻觉"
        print(f"     [{flag}] \"{cit.claim_text}\" (引用: {cit.cited_chunk_id or '自动推断'})")

    if audit_res.warnings:
        for w in audit_res.warnings:
            print(f"     [!] 预警: {w}")
    else:
        print("   -> 审计结论: 完全忠诚于官方公告，无事实性与忠诚性幻觉！")


if __name__ == "__main__":
    q = "诺安成长混合型证券投资基金2026年第2季度的运作方式、合同生效日、报告期末份额总额分别是多少？"
    asyncio.run(run_e2e_qa(q))
