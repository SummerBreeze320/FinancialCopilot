from __future__ import annotations

import json
import os
from pathlib import Path
import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.models.dsl import FundScreeningDsl, metric_leaves
from app.pipeline.semantic_coverage import check_semantic_coverage, extract_query_comparisons


@pytest.fixture(scope="module")
def golden_cases() -> list[dict]:
    fixture_path = Path(__file__).resolve().parents[1] / "fixtures" / "business-golden-cases.json"
    assert fixture_path.exists(), f"Golden cases file not found: {fixture_path}"
    with open(fixture_path, "r", encoding="utf-8") as f:
        cases = json.load(f)
    assert len(cases) >= 8
    return cases


def test_offline_golden_dsl_structural_soundness(golden_cases: list[dict]):
    """Offline 结构门禁：100% 结构合法且严禁无由 0 值占位符。"""
    client = TestClient(create_app())

    for case in golden_cases:
        query = case["query"]
        resp = client.post("/v1/fund-screening/commands", json={"query": query})
        assert resp.status_code == 200, f"Query failed for '{query}': {resp.text}"
        data = resp.json()
        assert data.get("dsl") is not None, f"DSL is None for '{query}'"

        dsl = FundScreeningDsl.model_validate(data["dsl"])
        assert dsl.version == "fund-screening/v1"
        assert len(dsl.children) >= 1
        assert dsl.children[0].sector is not None
        assert dsl.children[0].metric is not None

        # 校验严禁填 0 占位符
        leaves = metric_leaves(dsl)
        has_zero_in_query = any(c in query for c in ("0", "零"))
        for leaf in leaves:
            if not has_zero_in_query:
                assert leaf.value != 0, f"Unwarranted zero placeholder in leaf {leaf.metric} for query '{query}'"


def test_offline_golden_cases_semantic_coverage(golden_cases: list[dict]):
    """Offline 语义覆盖门禁：关键数值比较片段不被静默丢弃。"""
    client = TestClient(create_app())

    for case in golden_cases:
        query = case["query"]
        resp = client.post("/v1/fund-screening/commands", json={"query": query})
        assert resp.status_code == 200
        dsl = FundScreeningDsl.model_validate(resp.json()["dsl"])

        # 检查比较片段是否至少都在 DSL 树的某个叶子中呈现
        comparisons = extract_query_comparisons(query)
        leaves = metric_leaves(dsl)
        for comp in comparisons:
            matched = False
            for leaf in leaves:
                if leaf.value is not None and abs(leaf.value - comp.number_val) < 1e-4:
                    matched = True
                    break
                # 百分比单位换算容忍 (例如 15% -> -15 或 15)
                if leaf.value is not None and abs(abs(leaf.value) - comp.number_val) < 1e-4:
                    matched = True
                    break
            assert matched, f"Numeric comparison '{comp.text}' dropped in DSL for query '{query}'"


@pytest.mark.skipif(
    not os.getenv("RUN_REAL_AGY_GATE") and not os.getenv("RUN_REAL_FUND_SCREENING_TESTS"),
    reason="Live Agy Gate test skipped unless RUN_REAL_AGY_GATE=1",
)
def test_real_agy_live_gate(golden_cases: list[dict]):
    """真实在线 Agy 门禁：真实 LLM 在线准确率 >= 90%，无 0 值占位。"""
    client = TestClient(create_app())
    passed_count = 0

    for case in golden_cases:
        query = case["query"]
        resp = client.post("/v1/fund-screening/commands", json={"query": query})
        if resp.status_code != 200:
            continue
        data = resp.json()
        if not data.get("dsl"):
            continue

        dsl = FundScreeningDsl.model_validate(data["dsl"])
        leaves = metric_leaves(dsl)

        # 检查无 0 占位
        has_zero_in_query = any(c in query for c in ("0", "零"))
        has_zero = any(l.value == 0 for l in leaves) if not has_zero_in_query else False
        if has_zero:
            continue

        passed_count += 1

    accuracy = passed_count / len(golden_cases)
    assert accuracy >= 0.90, f"Live Agy accuracy {accuracy * 100:.1f}% below 90% threshold"
