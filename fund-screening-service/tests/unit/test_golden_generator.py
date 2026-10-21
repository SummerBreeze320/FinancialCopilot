import pytest
from pathlib import Path
from app.evaluation.loader import load_golden_dataset
from app.models.candidate import CandidateContract
from app.evaluation.generator import (
    generate_composite_scenario_cases,
    generate_metric_cases,
    generate_sector_cases,
    generate_entity_cases,
    generate_scenario_cases,
)


def test_generate_metric_cases_creates_structured_samples():
    m = CandidateContract(
        domain="metric",
        key="f_return_1y",
        name="近1年收益率",
        aliases=("近1年收益", "一年收益率"),
        supported_operators=(">", ">="),
    )
    cases = generate_metric_cases([m])
    assert len(cases) >= 1
    c = cases[0]
    assert c.category == "metric"
    assert "近1年收益" in c.query or "近1年收益率" in c.query
    assert c.expected_conditions[0].primary_candidate_key == "f_return_1y"


def test_generate_sector_cases_creates_scope_samples():
    s = CandidateContract(
        domain="scope",
        key="1000002533000000",
        name="普通股票型基金",
        aliases=("股票型", "股票基金"),
    )
    cases = generate_sector_cases([s])
    assert len(cases) >= 1
    c = cases[0]
    assert c.category == "sector"
    assert c.expected_conditions[0].primary_candidate_key == "1000002533000000"


def test_generate_entity_cases_creates_entity_samples():
    e = CandidateContract(
        domain="entity",
        key="000001",
        name="华夏成长混合",
        aliases=("华夏成长",),
    )
    cases = generate_entity_cases([e])
    assert len(cases) >= 1
    c = cases[0]
    assert c.category == "entity"
    assert c.expected_conditions[0].primary_candidate_key == "000001"


def test_generate_scenario_cases_includes_vague_and_complex_scenarios():
    cases = generate_scenario_cases()
    assert len(cases) >= 5
    outcomes = {c.expected_outcome for c in cases}
    assert "DSL" in outcomes
    assert "CLARIFICATION" in outcomes
    # Check for negative max drawdown scenario
    dd_cases = [c for c in cases if "回撤" in c.query]
    assert len(dd_cases) >= 1


def test_generate_composite_scenarios_cover_cross_domain_logic_and_multiturn():
    cases = generate_composite_scenario_cases()

    assert len(cases) >= 80
    assert all(len(case.expected_conditions) >= 2 for case in cases)
    assert any("或者" in case.query for case in cases)
    assert any("排除" in case.query for case in cases)
    assert any(case.expected_outcome == "CLARIFICATION" for case in cases)
    assert any("上一轮" in case.query for case in cases)
    assert any(
        {condition.domain for condition in case.expected_conditions} >= {"scope", "metric"}
        for case in cases
    )


def test_versioned_composite_dataset_preserves_chinese_logic_queries():
    golden_dir = Path(__file__).resolve().parents[2] / "data" / "golden"
    composite = [case for case in load_golden_dataset(golden_dir) if case.id.startswith("composite-")]

    assert len(composite) >= 80
    assert any("或者" in case.query for case in composite)
    assert any("排除" in case.query for case in composite)
    assert any("上一轮" in case.query for case in composite)
