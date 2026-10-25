"""验证全景投研知识库多文档联合检索与单文档定向透视。"""

import asyncio
import httpx
import sys

sys.stdout.reconfigure(encoding="utf-8")

test_cases = [
    (
        "all",
        "对比诺安成长2026年第1季度和第2季度的报告期末基金份额总额是多少？前三大重仓股票有何变动？",
    ),
    (
        "doc_ann_val_adj",
        "诺安基金对旗下基金持有的股票拓荆科技(688072)估值进行了怎样的调整？调整依据与生效日期是什么？",
    ),
    (
        "doc_fund_summary",
        "诺安成长混合型基金的管理费率和托管费率分别是多少？投资于股票资产的比例限制是多少？",
    ),
    (
        "doc_4102084",
        "诺安成长2026年第1季度末的基金总份额是多少？前三大重仓股票分别是什么？",
    ),
]


async def main():
    async with httpx.AsyncClient(trust_env=False, timeout=120) as client:
        for doc_id, q in test_cases:
            print(f"\n============================================================")
            print(f"【测试范围: {doc_id}】问题: {q}")
            print(f"============================================================")
            r = await client.post(
                "http://127.0.0.1:8820/api/knowledge/pipeline/trace",
                json={"query": q, "document_id": doc_id, "top_k": 3},
            )
            assert r.status_code == 200, f"Failed with {r.status_code}"
            data = r.json()
            p = data["pipeline"]
            s1 = p["stage_1_parsing"]
            s6 = p["stage_6_neural_rerank"]
            s7 = p["stage_7_generation_audit"]

            print(f"  Stage 1 识别文档: {s1['document_title']}")
            print(f"  Stage 6 选定切片数: {s6['selected_chunks_count']} 条")
            for c in s6["scored_candidates"]:
                if c.get("is_selected"):
                    print(f"     -> 来自【{c.get('doc_title')}】P.{c.get('page_number')}: {c.get('section_title')}")
            print(f"  Stage 7 事实忠诚度: {s7['faithfulness_score']*100:.0f}%")
            print(f"  Stage 7 生成回答:\n{s7['answer_text']}")


if __name__ == "__main__":
    asyncio.run(main())
