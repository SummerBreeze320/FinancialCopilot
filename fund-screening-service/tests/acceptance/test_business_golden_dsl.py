from __future__ import annotations

import json
from pathlib import Path
from typing import Any
import pytest

from app.models.dsl import FundScreeningDsl, metric_leaves
from app.models.dsl_nodes import MetricPredicate
from app.pipeline.compiler import CompileResult
from app.pipeline.validator import ValidationResult


def assert_business_dsl(case: dict[str, Any], result: CompileResult) -> None:
    assert result.dsl is not None, f"DSL should not be None for query: {case.get('query')}"
    leaves = metric_leaves(result.dsl)

    if "metrics" in case:
        actual_metrics = [leaf.metric for leaf in leaves]
        normalized_actual = ["f_netasset_total" if m == "f_info_fundscale" else m for m in actual_metrics]
        assert actual_metrics == case["metrics"] or normalized_actual == case["metrics"], (
            f"Metrics mismatch for query '{case.get('query')}': "
            f"expected {case['metrics']}, got {actual_metrics}"
        )

    if "limit" in case:
        assert result.dsl.limit == case["limit"], (
            f"Limit mismatch for query '{case.get('query')}': "
            f"expected {case['limit']}, got {result.dsl.limit}"
        )

    if "entityRole" in case:
        role = case["entityRole"]
        found = any(
            (leaf.params and any(p.get("role") == role for p in leaf.params))
            or (role == "FUND_COMPANY" and "company" in leaf.metric)
            or (role == "FUND_MANAGER" and "manager" in leaf.metric)
            or (role == "HOLDING_STOCK" and "stock" in leaf.metric)
            or (role == "TRACKED_INDEX" and ("index" in leaf.metric or "benchmark" in leaf.metric))
            for leaf in leaves
        )
        assert found, f"Expected entityRole '{role}' represented in DSL leaves for query: '{case.get('query')}'"


@pytest.fixture
def compiled_result() -> CompileResult:
    from fastapi.testclient import TestClient
    from app.main import create_app
    from app.pipeline.validator import ValidationResult

    client = TestClient(create_app())
    resp = client.post("/v1/fund-screening/commands", json={"query": "规模大于10亿且成立超过5年"})
    assert resp.status_code == 200
    dsl = FundScreeningDsl.model_validate(resp.json()["dsl"])
    return CompileResult(dsl=dsl, trace=resp.json().get("trace", {}), validation=ValidationResult(dsl))


def test_scale_and_existing_year_are_both_preserved(compiled_result: CompileResult) -> None:
    leaves = metric_leaves(compiled_result.dsl)
    metrics_map = {x.metric: x for x in leaves}
    # Scale can be f_netasset_total or f_info_fundscale
    scale_leaf = metrics_map.get("f_netasset_total") or metrics_map.get("f_info_fundscale")
    assert scale_leaf is not None, f"Scale metric missing from leaves: {list(metrics_map.keys())}"
    assert scale_leaf.operator in (">", ">=")
    assert scale_leaf.value in (10, 10.0, 1_000_000_000, 1_000_000_000.0)

    year_leaf = metrics_map.get("f_info_existingyear")
    assert year_leaf is not None, f"Existing year metric missing from leaves: {list(metrics_map.keys())}"
    assert year_leaf.operator in (">", ">=")
    assert year_leaf.value == 5
    assert year_leaf.value != 0, "Existing year must not be zero placeholder"


def test_golden_cases_fixture_structure() -> None:
    fixture_path = Path(__file__).resolve().parents[1] / "fixtures" / "business-golden-cases.json"
    assert fixture_path.exists(), f"Fixture file not found: {fixture_path}"
    with open(fixture_path, "r", encoding="utf-8") as f:
        cases = json.load(f)
    assert len(cases) >= 8, f"Expected at least 8 golden cases, found {len(cases)}"
    for case in cases:
        assert "query" in case


def test_all_golden_cases_acceptance() -> None:
    from fastapi.testclient import TestClient
    from app.main import create_app

    fixture_path = Path(__file__).resolve().parents[1] / "fixtures" / "business-golden-cases.json"
    with open(fixture_path, "r", encoding="utf-8") as f:
        cases = json.load(f)

    client = TestClient(create_app())
    for case in cases:
        resp = client.post("/v1/fund-screening/commands", json={"query": case["query"]})
        assert resp.status_code == 200, f"Query failed for '{case['query']}': {resp.text}"
        data = resp.json()
        assert data.get("dsl") is not None, f"DSL is None for '{case['query']}'"
        dsl = FundScreeningDsl.model_validate(data["dsl"])
        res = CompileResult(dsl=dsl, trace=data.get("trace", {}), validation=ValidationResult(dsl))
        assert_business_dsl(case, res)
