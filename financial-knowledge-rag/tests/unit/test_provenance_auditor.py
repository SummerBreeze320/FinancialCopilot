"""单元测试：生成器事实溯源与忠诚性/事实性幻觉审计器。"""

import pytest
from app.generator.provenance_auditor import ProvenanceAuditor


def test_audit_faithful_response():
    auditor = ProvenanceAuditor()
    chunks = [
        {
            "id": "c1",
            "chunk_id": "c1",
            "cognitive_layer": "GROUND_TRUTH",
            "content": "2024Q3公司实现单季度营业收入922.78亿元，毛利率为31.17%。",
            "doc_title": "宁德时代2024Q3季报",
        }
    ]

    generated_text = "根据三季报，宁德时代2024Q3营业收入为922.78亿元 [1]，毛利率达到31.17% [1]。"

    report = auditor.audit_response(generated_text, chunks)
    assert report.total_claims >= 1
    assert report.faithfulness_score == 1.0
    for claim in report.claims:
        assert claim.is_faithful is True
        assert claim.hallucination_type == "NONE"


def test_audit_faithfulness_hallucination():
    auditor = ProvenanceAuditor()
    chunks = [
        {
            "id": "c1",
            "chunk_id": "c1",
            "cognitive_layer": "GROUND_TRUTH",
            "content": "2024Q3公司综合毛利率为31.17%。动力电池出货量98.5GWh。",
            "doc_title": "宁德时代三季报",
        }
    ]

    # 模型自由发挥，捏造了 45.5% 的毛利率和 150GWh 的出货量
    generated_text = "宁德时代单季度毛利率大幅跃升至45.5% [1]，并且动力电池出货量突破了150GWh [1]。"

    report = auditor.audit_response(generated_text, chunks)
    assert report.faithfulness_score < 1.0
    assert any(c.hallucination_type == "FAITHFULNESS_HALLUCINATION" for c in report.claims)
    assert any(not c.is_faithful for c in report.claims)


def test_audit_factuality_conflict():
    auditor = ProvenanceAuditor()
    chunks = [
        {
            "id": "c_opinion",
            "chunk_id": "c_opinion",
            "cognitive_layer": "OPINION",
            "content": "中金公司预测宁德时代2024Q3归母净利润预计为150.00亿元。",
            "doc_title": "中金公司研报",
        },
        {
            "id": "c_truth",
            "chunk_id": "c_truth",
            "cognitive_layer": "GROUND_TRUTH",
            "content": "2024Q3公司法定披露的归属于上市公司股东净利润实际为131.36亿元。",
            "doc_title": "宁德时代2024年三季度报告（法定）",
        },
    ]

    # 回答忠实引用了券商预测，但与法定事实冲突
    generated_text = "根据中金公司研报，预计宁德时代2024Q3归母净利润为150.00亿元 [1]。"

    report = auditor.audit_response(generated_text, chunks)
    # 属于忠实引用（未产生忠诚性幻觉），但应提示事实性冲突
    assert any("事实冲突" in w or "Factuality Conflict" in w for w in report.warnings)
