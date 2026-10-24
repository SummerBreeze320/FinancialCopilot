import pytest
from app.models.candidate import CandidateContract
from app.retrieval.candidate_ranker import rank_candidates


def test_exact_name_match_preferred_over_alias():
    c_alias = CandidateContract(
        domain="metric",
        key="m_alias",
        name="收益指标",
        aliases=("近一年收益率",),
        score=0.95,
    )
    c_name = CandidateContract(
        domain="metric",
        key="m_name",
        name="近一年收益率",
        aliases=("1年收益",),
        score=0.70,
    )
    ranked = rank_candidates("近一年收益率", [c_alias, c_name])
    assert ranked[0].key == "m_name"
    assert ranked[1].key == "m_alias"


def test_exact_alias_match_preferred_over_vector_score():
    c_vector = CandidateContract(
        domain="metric",
        key="m_vector",
        name="相关指标",
        aliases=("其他",),
        score=0.98,
    )
    c_alias = CandidateContract(
        domain="metric",
        key="m_alias",
        name="复权单位净值增长率",
        aliases=("区间回报", "指定日期区间收益"),
        score=0.75,
    )
    ranked = rank_candidates("区间回报", [c_vector, c_alias])
    assert ranked[0].key == "m_alias"
    assert ranked[1].key == "m_vector"


def test_etf_selects_highest_default_priority_sector():
    c_stock_etf = CandidateContract(
        domain="scope",
        key="1000002541000000",
        name="股票型ETF",
        aliases=("ETF", "股票ETF"),
        default_priority=10,
        score=0.95,
    )
    c_etf_main = CandidateContract(
        domain="scope",
        key="1000002540000000",
        name="ETF(场内)",
        aliases=("ETF", "场内ETF"),
        default_priority=100,
        score=0.90,
    )
    ranked = rank_candidates("ETF", [c_stock_etf, c_etf_main])
    assert ranked[0].key == "1000002540000000"


def test_in_market_non_monetary_etf_selects_best_qualifier_coverage():
    c_etf_in = CandidateContract(
        domain="scope",
        key="s_etf_in",
        name="ETF(场内)",
        aliases=("场内ETF",),
        score=0.95,
    )
    c_nomoney = CandidateContract(
        domain="scope",
        key="s_nomoney",
        name="非货币基金",
        aliases=(),
        score=0.90,
    )
    c_best = CandidateContract(
        domain="scope",
        key="s_etf_in_nomoney",
        name="场内非货币ETF",
        aliases=("场内非货ETF",),
        score=0.80,
    )
    ranked = rank_candidates("场内非货币ETF", [c_etf_in, c_nomoney, c_best])
    assert ranked[0].key == "s_etf_in_nomoney"


def test_combined_scale_selects_metric_with_explicit_alias():
    c_scale = CandidateContract(
        domain="metric",
        key="f_info_fundscale",
        name="基金规模",
        aliases=("规模",),
        score=0.95,
    )
    c_netasset = CandidateContract(
        domain="metric",
        key="f_netasset_total",
        name="净资产(元)",
        aliases=("合并规模", "资产净值"),
        score=0.82,
    )
    ranked = rank_candidates("合并规模", [c_scale, c_netasset])
    assert ranked[0].key == "f_netasset_total"


def test_stable_tie_break_by_id_when_scores_equal():
    c1 = CandidateContract(
        domain="metric",
        key="metric_b",
        name="测试指标",
        score=0.90,
    )
    c2 = CandidateContract(
        domain="metric",
        key="metric_a",
        name="测试指标",
        score=0.90,
    )
    ranked = rank_candidates("测试指标", [c1, c2])
    assert [c.key for c in ranked] == ["metric_a", "metric_b"]


def test_deduplicates_same_key_keeping_highest_source():
    c1 = CandidateContract(
        domain="metric",
        key="m1",
        name="指标1",
        score=0.70,
    )
    c2 = CandidateContract(
        domain="metric",
        key="m1",
        name="指标1",
        score=0.95,
    )
    ranked = rank_candidates("指标1", [c1, c2])
    assert len(ranked) == 1
    assert ranked[0].score == 0.95
