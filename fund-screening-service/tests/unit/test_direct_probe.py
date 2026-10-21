import pytest
from app.graph.direct_probe import probe_strict_direct
from app.models.candidate import CandidateContract


def test_probe_strict_direct_pure_fund_code():
    candidates = [
        CandidateContract(
            domain="entity",
            key="001938.OF",
            name="中欧时代先锋股票A",
            aliases=("001938",),
            binding_role="FUND",
            preferred_metric="f_info_fundcode",
            supported_operators=("=", "!="),
        )
    ]
    is_direct, direct_dsl, reason = probe_strict_direct("001938", candidates)
    assert is_direct is True
    assert direct_dsl is not None
    assert reason == "EXACT_FUND_CODE"
    assert direct_dsl.children[0].metric.children[0].metric == "f_info_windcode"
    assert direct_dsl.children[0].metric.children[0].value == "001938.OF"


def test_probe_rejects_query_with_or_logic():
    candidates = [
        CandidateContract(domain="metric", key="f_info_existingyear", name="成立年限")
    ]
    is_direct, direct_dsl, reason = probe_strict_direct("成立大于5年或者大于3年", candidates)
    assert is_direct is False
    assert direct_dsl is None
    assert "OR_OR_NOT_DETECTED" in reason


def test_probe_strict_direct_exact_fund_name():
    candidates = [
        CandidateContract(
            domain="entity",
            key="001938.OF",
            name="中欧时代先锋股票A",
            aliases=("中欧时代先锋",),
            binding_role="FUND",
            preferred_metric="f_info_fundcode",
            supported_operators=("=", "!="),
        )
    ]
    is_direct, direct_dsl, reason = probe_strict_direct("中欧时代先锋股票A", candidates)
    assert is_direct is True
    assert direct_dsl is not None
    assert reason == "EXACT_ENTITY_MATCH"
    assert direct_dsl.children[0].metric.children[0].value == "001938.OF"


def test_probe_strict_direct_simple_edit_with_previous_dsl():
    from app.models.dsl import BusinessGroup, SectorExpression, SectorLeaf
    previous_dsl = {
        "version": "fund-screening/v1",
        "operator": "AND",
        "children": [
            {
                "sector": {"operator": "OR", "children": [{"sectorId": "1000019220000000"}]},
                "metric": {"operator": "AND", "children": [{"metric": "f_info_fundscale", "operator": ">", "value": 10.0}]},
            }
        ],
        "limit": 20,
    }
    is_direct, direct_dsl, reason = probe_strict_direct("改成前50只", [], previous_dsl=previous_dsl)
    assert is_direct is True
    assert direct_dsl is not None
    assert reason == "SIMPLE_EDIT_DIRECT"
    assert direct_dsl.limit == 50

