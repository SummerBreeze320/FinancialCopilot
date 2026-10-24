from __future__ import annotations

import pytest
from app.graph.direct_probe import probe_strict_direct
from app.models.candidate import CandidateContract
from app.pipeline.intent_funnel import IntentFunnel, IntentType


def test_intent_funnel_strictly_outputs_create_or_edit():
    funnel = IntentFunnel()
    res1 = funnel.classify_intent("筛选近一年收益大于10%的基金", previous_dsl=None)
    assert res1.intent in (IntentType.CREATE, IntentType.EDIT)
    assert not hasattr(res1, "route")  # No route coupling

    prev_dsl = {"version": "fund-screening/v1", "children": []}
    res2 = funnel.classify_intent("把规模改成20亿", previous_dsl=prev_dsl)
    assert res2.intent in (IntentType.CREATE, IntentType.EDIT)


def test_strict_direct_probe_rejects_ambiguity_and_descriptions():
    candidates = [
        CandidateContract(
            domain="metric",
            key="f_return_1y",
            name="近1年收益率",
            supported_operators=(">", ">="),
            value_type="PERCENT",
        )
    ]
    # Fuzzy query must not be bypassed by direct probe
    ok, dsl, reason = probe_strict_direct("收益好的基金", candidates)
    assert ok is False
    assert dsl is None

    # Multi-condition query must not be bypassed
    ok2, dsl2, reason2 = probe_strict_direct("规模大于10亿且成立超过3年", candidates)
    assert ok2 is False
    assert dsl2 is None

    # Ranking / subjective terms must not be bypassed
    ok3, dsl3, reason3 = probe_strict_direct("近一年收益排名前十", candidates)
    assert ok3 is False
    assert dsl3 is None


def test_strict_direct_probe_accepts_provable_exact_query():
    candidates = [
        CandidateContract(
            domain="metric",
            key="f_info_fundscale",
            name="基金规模",
            supported_operators=(">", ">="),
            value_type="BILLION_CNY",
            aliases=("规模",),
        )
    ]
    ok, dsl, reason = probe_strict_direct("规模>10亿", candidates)
    assert ok is True
    assert dsl is not None
    assert reason == "STRICT_SINGLE_METRIC"


def test_strict_direct_probe_accepts_exact_fund_code():
    ok, dsl, reason = probe_strict_direct("001938", [])
    assert ok is True
    assert dsl is not None
    assert reason == "EXACT_FUND_CODE"
    assert dsl.children[0].metric.children[0].value == "001938.OF"
