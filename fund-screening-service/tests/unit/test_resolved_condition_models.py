from __future__ import annotations

import pytest
from app.models.candidate import ConditionCandidateView
from app.models.semantic_plan import ConditionBinding, ResolvedCondition


def test_condition_candidate_view_provenance():
    view = ConditionCandidateView(
        condition_id="c_1",
        candidate_type="metric",
        canonical_id="f_return_1y",
        display_name="近1年收益率",
        aliases_matched=["近一年收益"],
        retrieval_channel="pgvector",
        retrieval_score=0.92,
        rank=1,
        parameter_schema=[],
        storage_convention="PERCENT_POSITIVE",
    )
    assert view.condition_id == "c_1"
    assert view.retrieval_channel == "pgvector"
    assert view.rank == 1
    assert view.storage_convention == "PERCENT_POSITIVE"


def test_resolved_condition_immutability_and_validation():
    resolved = ResolvedCondition(
        conditionId="c_1",
        selectedCandidateId="f_info_fundscale",
        operator=">",
        value=10.0,
        unit="亿",
        params={},
        confidence=0.98,
    )
    assert resolved.condition_id == "c_1"
    assert resolved.selected_candidate_id == "f_info_fundscale"
    assert resolved.confidence == 0.98
    # Backward compatibility with candidate_key / candidateKey
    assert resolved.candidate_key == "f_info_fundscale"


def test_condition_binding_compatibility_alias():
    binding = ConditionBinding(
        conditionId="c_2",
        candidateKey="f_info_fundscale",
        operator=">",
        value=10.0,
    )
    assert binding.condition_id == "c_2"
    assert binding.candidate_key == "f_info_fundscale"
    assert binding.selected_candidate_id == "f_info_fundscale"


def test_resolved_metric_condition_requires_value():
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        ResolvedCondition(
            conditionId="m1",
            selectedCandidateId="f_netasset_total",
            operator=">",
        )


def test_discriminated_condition_binding_contracts():
    from pydantic import ValidationError
    from app.models.semantic_plan import (
        MetricConditionBinding,
        ScopeConditionBinding,
        EntityConditionBinding,
    )

    m = MetricConditionBinding(conditionId="m1", domain="metric", candidateKey="f_info_existingyear", operator=">", value=5)
    assert m.condition_id == "m1"
    assert m.value == 5
    assert m.domain == "metric"

    s = ScopeConditionBinding(conditionId="s1", domain="scope", candidateKey="1000019220000000")
    assert s.condition_id == "s1"
    assert s.candidate_key == "1000019220000000"
    assert s.value is None

    e = EntityConditionBinding(conditionId="e1", domain="entity", candidateKey="manager:张坤", bindingRole="FUND_MANAGER")
    assert e.condition_id == "e1"
    assert e.binding_role == "FUND_MANAGER"
    assert e.value is None

    with pytest.raises(ValidationError):
        MetricConditionBinding(conditionId="m2", domain="metric", candidateKey="f_info_existingyear", operator=">", value=None)

