import pytest
from memory_service.engine.extraction.mem0_extractor import Mem0FactExtractor
from memory_service.engine.extraction.reme_distiller import ReMeExperienceDistiller
from memory_service.domain.procedure import Procedure
from memory_service.domain.enums import ConsolidationAction


def test_mem0_fact_extractor_rule_based():
    extractor = Mem0FactExtractor()
    messages = [
        {"role": "user", "content": "我的风险偏好是进取型，打算长期持有，每月预算5000元，主要配置ETF和债基，不要白酒"},
    ]
    facts, evidences = extractor.extract_facts("session_test_01", messages)
    
    assert len(facts) >= 5
    predicates = {f.predicate: f.value for f in facts}
    assert predicates.get("risk_tolerance") == "进取型"
    assert predicates.get("investment_horizon") == "长期"
    assert "5000" in str(predicates.get("monthly_budget"))
    assert "ETF" in predicates.get("preferred_asset_classes", [])
    assert "白酒" in predicates.get("excluded_sectors", [])
    assert len(evidences) == len(facts)


def test_mem0_fact_extractor_llm_callable():
    mock_llm = lambda prompt, msgs: '[{"subject": "user", "predicate": "max_drawdown", "value": "15%", "confidence": 0.99}]'
    extractor = Mem0FactExtractor(llm_callable=mock_llm)
    messages = [{"role": "user", "content": "我最大能承受15%的回撤"}]
    facts, evidences = extractor.extract_facts("session_test_02", messages)
    
    assert len(facts) == 1
    assert facts[0].predicate == "max_drawdown"
    assert facts[0].value == "15%"
    assert evidences[0].confidence == 0.99


def test_reme_experience_distiller_and_refinement():
    distiller = ReMeExperienceDistiller()
    trajectory = [
        {"name": "fund_screening_tool", "arguments": {"sector": "新能源"}},
        {"name": "fund_comparison_tool", "arguments": {"codes": ["000001", "000002"]}},
    ]
    errors = [{"code": "DATA_TIMEOUT", "message": "Remote Fund NAV API response timed out"}]

    proc, evidence = distiller.distill_from_trajectory(
        task_type="fund_analysis",
        trajectory_events=trajectory,
        outcome="FAILED",
        errors=errors,
        source_ref="run:test-run-123",
    )

    assert proc.task_type == "fund_analysis"
    assert len(proc.steps) == 2
    assert any("DATA_TIMEOUT" in f for f in proc.failure_signatures)
    assert any("exponential backoff" in r for r in proc.recovery_actions)
    assert evidence.confidence == 0.7

    # Test ReMe Utility-based Refinement
    # 1. Identical -> CORROBORATE
    action, refined = distiller.utility_refine(proc, proc)
    assert action == ConsolidationAction.CORROBORATE

    # 2. Add new error signature & recovery -> REFINE
    new_proc = Procedure(
        task_type="fund_analysis",
        trigger_conditions=proc.trigger_conditions,
        preconditions=proc.preconditions,
        steps=proc.steps,
        applicability=proc.applicability,
        validation_rules=proc.validation_rules,
        failure_signatures=proc.failure_signatures + ["[KYC_RESTRICTED] User risk tier low"],
        recovery_actions=proc.recovery_actions + ["If KYC_RESTRICTED: Reprompt user"],
    )
    action, refined = distiller.utility_refine(proc, new_proc)
    assert action == ConsolidationAction.REFINE
    assert len(refined.failure_signatures) == len(proc.failure_signatures) + 1
