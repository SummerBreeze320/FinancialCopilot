# Fund Screening pgvector & DSL Golden Evaluation Closed-Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a versioned, analyst-grade golden evaluation dataset and automated evaluation closed-loop that measures condition-level pgvector/lexical retrieval quality (P@K, R@K, Hit@K, MRR, nDCG@K) and end-to-end DSL/clarification accuracy, with 7-class failure attribution and actionable remediation backlogs.

**Architecture:**
1. **Dataset Organization & Schema:** Golden samples partitioned into `metrics.jsonl`, `sectors-top100.jsonl`, `entities-top100.jsonl`, `research-scenarios.jsonl`, and `regressions.jsonl`. Pydantic-based schema with strict PII anonymization validation for regressions.
2. **Scaffolding Generator:** Generates analyst-style query candidates and golden cases across all enabled metrics, top 100 sectors, top 100 entities, and complex combination scenarios.
3. **Multi-Channel & Multi-Stage Evaluator:** Evaluates queries through condition-level retrieval (pgvector metric, pgvector scope, lexical entity, merged pool) and end-to-end LangGraph compilation.
4. **IR & DSL Metrics Engine:** Computes Precision@K, Recall@K, Hit@1/3/10, MRR, nDCG@K per condition/domain/channel/global, alongside DSL exact/structural match and clarification precision/recall.
5. **Attribution & Backlog Generator:** Classifies failures into `alias_missing`, `embedding_text_insufficient`, `vector_recall_weak`, `rerank_issue`, `plan_error`, `adjudication_error`, and `validator_error`, outputting machine-readable JSON and human-readable Markdown reports.

**Tech Stack:** Python 3.11, Pydantic v2, pytest, NumPy / pure Python math for ranking metrics, JSONLines.

**Spec:** [docs/superpowers/specs/2026-09-30-fund-screening-pgvector-golden-evaluation-design.md](file:///d:/BaiduSyncdisk/IdeaProjects/FinancialCopilot/.worktrees/fund-screening-agent/docs/superpowers/specs/2026-09-30-fund-screening-pgvector-golden-evaluation-design.md)

## Global Constraints

- Golden dataset must cover all enabled metrics, top 100 sectors, top 100 entities, and complex investment research scenarios.
- Vague metric queries (e.g., "收益好的", "风险低的") MUST expect `CLARIFICATION` and reject implicit metric selection.
- `regressions.jsonl` MUST be scrubbed of all user IDs, account numbers, conversation IDs, and sensitive tokens.
- Ranking metrics MUST compute across individual channels (lexical entity, pgvector metric, pgvector scope) as well as the merged candidate pool.
- Evaluation reports MUST produce both machine-readable JSON (`evaluation_report.json`) and Markdown summary (`evaluation_report.md`).
- Failure attribution MUST produce actionable data governance and model tuning backlog items.

---

### Task 1: Golden Dataset Schema, Loader & PII Sanitizer

**Files:**
- Create: `fund-screening-service/app/evaluation/schema.py`
- Modify: `fund-screening-service/app/evaluation/models.py`
- Create: `fund-screening-service/app/evaluation/loader.py`
- Test: `fund-screening-service/tests/unit/test_golden_schema.py`

**Interfaces:**
- Produces:
  - `ExpectedCondition(mention: str, domain: Literal["metric", "scope", "entity"], relevant_candidate_keys: list[str], primary_candidate_key: str | None = None)`
  - `GoldenCase(id: str, category: str, query: str, difficulty: str, expected_outcome: str, expected_conditions: list[ExpectedCondition], expected_dsl: dict | None, expected_clarification: dict | None)`
  - `load_golden_dataset(directory_or_files: list[Path] | Path) -> list[GoldenCase]`
  - `validate_and_sanitize_regression_case(case_dict: dict[str, Any]) -> dict[str, Any]` (strips tokens matching phone, user_id, conversation_id, account, etc.)

- [x] **Step 1: Write failing unit test for GoldenCase schema, loader and PII sanitization**

```python
# fund-screening-service/tests/unit/test_golden_schema.py
import pytest
from app.evaluation.schema import GoldenCase, ExpectedCondition, validate_and_sanitize_regression_case
from app.evaluation.loader import load_golden_dataset


def test_golden_case_validation():
    case = GoldenCase.model_validate({
        "id": "metric-return-1y",
        "category": "metric",
        "query": "近1年收益率超过10%",
        "difficulty": "clear",
        "expectedOutcome": "DSL",
        "expectedConditions": [
            {
                "mention": "近1年收益率超过10%",
                "domain": "metric",
                "relevantCandidateKeys": ["f_nav_return_1y", "f_return_1y"],
                "primaryCandidateKey": "f_return_1y",
            }
        ],
        "expectedDsl": {"version": "fund-screening/v1"},
    })
    assert case.id == "metric-return-1y"
    assert case.expected_conditions[0].primary_candidate_key == "f_return_1y"
    assert len(case.expected_conditions[0].relevant_candidate_keys) == 2


def test_vague_query_clarification_expectation():
    case = GoldenCase.model_validate({
        "id": "vague-high-return",
        "category": "scenario",
        "query": "收益好的基金",
        "difficulty": "ambiguous",
        "expectedOutcome": "CLARIFICATION",
        "expectedConditions": [
            {
                "mention": "收益好的",
                "domain": "metric",
                "relevantCandidateKeys": ["f_return_1y", "f_return_3y"],
            }
        ],
        "expectedClarification": {"code": "AMBIGUOUS_METRIC"},
    })
    assert case.expected_outcome == "CLARIFICATION"
    assert case.expected_clarification["code"] == "AMBIGUOUS_METRIC"


def test_pii_sanitization_removes_sensitive_data():
    dirty_case = {
        "id": "user-fail-1",
        "category": "regression",
        "query": "帮张三(账号6222021234567890, 电话13800138000)筛选易方达蓝筹",
        "userId": "usr_998877",
        "conversationId": "conv_secret_123",
        "expectedOutcome": "DSL",
        "expectedConditions": [],
    }
    clean_case = validate_and_sanitize_regression_case(dirty_case)
    assert "userId" not in clean_case
    assert "conversationId" not in clean_case
    assert "6222021234567890" not in clean_case["query"]
    assert "13800138000" not in clean_case["query"]
```

- [x] **Step 2: Run test to verify it fails**

Run: `pytest tests/unit/test_golden_schema.py -v`
Expected: FAIL (ModuleNotFoundError: No module named 'app.evaluation.schema')

- [x] **Step 3: Implement `schema.py` and `loader.py`**

Create `fund-screening-service/app/evaluation/schema.py`:
```python
from __future__ import annotations

import re
from typing import Any, Literal
from pydantic import BaseModel, ConfigDict, Field


class ExpectedCondition(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    mention: str
    domain: Literal["metric", "scope", "entity"]
    relevant_candidate_keys: list[str] = Field(alias="relevantCandidateKeys")
    primary_candidate_key: str | None = Field(default=None, alias="primaryCandidateKey")


class GoldenCase(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    category: Literal["metric", "sector", "entity", "scenario", "regression"]
    query: str
    difficulty: Literal["clear", "ambiguous", "complex"] = "clear"
    expected_outcome: Literal["DSL", "CLARIFICATION"] = Field(default="DSL", alias="expectedOutcome")
    expected_conditions: list[ExpectedCondition] = Field(default_factory=list, alias="expectedConditions")
    expected_dsl: dict[str, Any] | None = Field(default=None, alias="expectedDsl")
    expected_clarification: dict[str, Any] | None = Field(default=None, alias="expectedClarification")


_SENSITIVE_KEYS = {"userId", "user_id", "accountId", "account_id", "conversationId", "conversation_id", "sessionId", "session_id"}

_PII_PATTERNS = [
    (r"\b1[3-9]\d{9}\b", "[PHONE]"),
    (r"\b\d{16,19}\b", "[ACCOUNT]"),
    (r"(?:身份证|ID)[\s:：]*\d{17}[\dXx]", "[ID_CARD]"),
]


def validate_and_sanitize_regression_case(case_dict: dict[str, Any]) -> dict[str, Any]:
    sanitized = {k: v for k, v in case_dict.items() if k not in _SENSITIVE_KEYS}
    query = sanitized.get("query", "")
    for pattern, repl in _PII_PATTERNS:
        query = re.sub(pattern, repl, query)
    sanitized["query"] = query
    return sanitized
```

Create `fund-screening-service/app/evaluation/loader.py`:
```python
from __future__ import annotations

import json
from pathlib import Path
from typing import Iterable
from app.evaluation.schema import GoldenCase


def load_golden_dataset(paths: Iterable[Path] | Path) -> list[GoldenCase]:
    if isinstance(paths, Path):
        file_list = list(paths.glob("*.jsonl")) if paths.is_dir() else [paths]
    else:
        file_list = list(paths)

    cases: list[GoldenCase] = []
    for filepath in sorted(file_list):
        if not filepath.exists():
            continue
        with open(filepath, "r", encoding="utf-8") as f:
            for line_no, line in enumerate(f, 1):
                clean_line = line.strip()
                if not clean_line or clean_line.startswith("#"):
                    continue
                try:
                    data = json.loads(clean_line)
                    cases.append(GoldenCase.model_validate(data))
                except Exception as exc:
                    raise ValueError(f"Failed parsing golden case at {filepath}:{line_no}: {exc}") from exc
    return cases
```

- [x] **Step 4: Run tests to verify they pass**

Run: `pytest tests/unit/test_golden_schema.py -v`
Expected: PASS

---

### Task 2: Golden Dataset Scaffolding Generator & Real Golden Sample Files

**Files:**
- Create: `fund-screening-service/app/evaluation/generator.py`
- Create: `fund-screening-service/data/golden/metrics.jsonl`
- Create: `fund-screening-service/data/golden/sectors-top100.jsonl`
- Create: `fund-screening-service/data/golden/entities-top100.jsonl`
- Create: `fund-screening-service/data/golden/research-scenarios.jsonl`
- Create: `fund-screening-service/data/golden/regressions.jsonl`
- Test: `fund-screening-service/tests/unit/test_golden_generator.py`

**Interfaces:**
- Consumes: `Catalog` from `app.catalog.loader`.
- Produces:
  - `generate_metric_cases(metrics: list[CandidateContract]) -> list[GoldenCase]`
  - `generate_sector_cases(sectors: list[CandidateContract], top_n: int = 100) -> list[GoldenCase]`
  - `generate_entity_cases(entities: list[CandidateContract], top_n: int = 100) -> list[GoldenCase]`
  - `generate_scenario_cases() -> list[GoldenCase]`
  - Complete `.jsonl` files in `fund-screening-service/data/golden/`.

- [x] **Step 1: Write failing unit test for dataset generation**

```python
# fund-screening-service/tests/unit/test_golden_generator.py
import pytest
from app.models.candidate import CandidateContract
from app.evaluation.generator import generate_metric_cases, generate_sector_cases, generate_scenario_cases


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


def test_generate_scenario_cases_includes_vague_and_complex_scenarios():
    cases = generate_scenario_cases()
    assert len(cases) >= 5
    outcomes = {c.expected_outcome for c in cases}
    assert "DSL" in outcomes
    assert "CLARIFICATION" in outcomes
    # Check for negative max drawdown scenario
    dd_cases = [c for c in cases if "回撤" in c.query]
    assert len(dd_cases) >= 1
```

- [x] **Step 2: Run test to verify it fails**

Run: `pytest tests/unit/test_golden_generator.py -v`
Expected: FAIL (ModuleNotFoundError: No module named 'app.evaluation.generator')

- [x] **Step 3: Implement `app/evaluation/generator.py` and populate `data/golden/*.jsonl`**

In `fund-screening-service/app/evaluation/generator.py`:
Implement generator logic for metrics, sectors, entities, and research scenarios (including vague queries expecting clarification, multi-condition AND/OR, date intervals, negative max drawdown, and multi-round rebuild).
Generate initial curated sample files in `fund-screening-service/data/golden/`:
- `metrics.jsonl`
- `sectors-top100.jsonl`
- `entities-top100.jsonl`
- `research-scenarios.jsonl`
- `regressions.jsonl`

- [x] **Step 4: Run tests to verify they pass**

Run: `pytest tests/unit/test_golden_generator.py -v`
Expected: PASS

---

### Task 3: IR & End-to-End Metrics Calculator (P@K, R@K, Hit@K, MRR, nDCG@K, DSL Matching)

**Files:**
- Create: `fund-screening-service/app/evaluation/metrics.py`
- Test: `fund-screening-service/tests/unit/test_ranking_metrics.py`

**Interfaces:**
- Produces:
  - `compute_ranking_metrics(recalled_keys: list[str], relevant_keys: list[str], primary_key: str | None = None, k_list: tuple[int, ...] = (1, 3, 5, 10)) -> dict[str, float]`
    Calculates `precision@K`, `recall@K`, `hit@K`, `mrr`, and `ndcg@K` (with relevance discount).
  - `compute_dsl_match(actual_dsl: dict | None, expected_dsl: dict | None) -> dict[str, bool | float]`
    Calculates `exact_match`, `structural_match`, `metrics_match`, `sectors_match`, `params_match`, `sort_match`, `limit_match`.
  - `compute_clarification_match(actual_clar: dict | None, expected_clar: dict | None) -> dict[str, bool]`

- [x] **Step 1: Write failing unit test for ranking & DSL metrics calculation**

```python
# fund-screening-service/tests/unit/test_ranking_metrics.py
import pytest
from app.evaluation.metrics import compute_ranking_metrics, compute_dsl_match, compute_clarification_match


def test_ranking_metrics_calculation():
    recalled = ["k1", "k2", "k3", "k4", "k5"]
    relevant = ["k2", "k5"]
    primary = "k2"

    res = compute_ranking_metrics(recalled, relevant, primary, k_list=(1, 3, 5))
    # Hit@1 = 0, Hit@3 = 1 (k2 at rank 2), Hit@5 = 1
    assert res["hit@1"] == 0.0
    assert res["hit@3"] == 1.0
    assert res["hit@5"] == 1.0

    # Precision@3 = 1 / 3, Recall@3 = 1 / 2
    assert pytest.approx(res["precision@3"], 0.01) == 0.333
    assert res["recall@3"] == 0.5

    # MRR: first relevant is k2 at rank 2 -> 1/2 = 0.5
    assert res["mrr"] == 0.5

    # nDCG@3: DCG has k2 (relevance 2) at position 2 -> 2 / log2(3)
    assert res["ndcg@3"] > 0.0
    assert res["ndcg@3"] <= 1.0


def test_dsl_structural_match():
    expected = {
        "version": "fund-screening/v1",
        "operator": "AND",
        "children": [
            {
                "sector": {"children": [{"sectorId": "100"}]},
                "metric": {"children": [{"metric": "f_scale", "operator": ">", "value": 10.0}]},
            }
        ],
        "limit": 20,
    }
    actual = {
        "version": "fund-screening/v1",
        "operator": "AND",
        "children": [
            {
                "sector": {"children": [{"sectorId": "100"}]},
                "metric": {"children": [{"metric": "f_scale", "operator": ">", "value": 10.0, "unit": "BILLION"}]},
            }
        ],
        "limit": 20,
    }
    match = compute_dsl_match(actual, expected)
    assert match["structural_match"] is True
    assert match["metrics_match"] is True
    assert match["sectors_match"] is True
    assert match["limit_match"] is True
```

- [x] **Step 2: Run test to verify it fails**

Run: `pytest tests/unit/test_ranking_metrics.py -v`
Expected: FAIL (ModuleNotFoundError: No module named 'app.evaluation.metrics')

- [x] **Step 3: Implement `app/evaluation/metrics.py`**

Implement deterministic calculations for Precision@K, Recall@K, Hit@K, MRR, nDCG@K, structural AST DSL matching, and clarification comparison.

- [x] **Step 4: Run tests to verify they pass**

Run: `pytest tests/unit/test_ranking_metrics.py -v`
Expected: PASS

---

### Task 4: Multi-Channel Evaluator Runner & Failure Attribution Engine

**Files:**
- Create: `fund-screening-service/app/evaluation/attribution.py`
- Create: `fund-screening-service/app/evaluation/runner.py`
- Test: `fund-screening-service/tests/unit/test_evaluation_runner.py`

**Interfaces:**
- Consumes: `GoldenCase`, `compute_ranking_metrics`, `compute_dsl_match`, `compute_clarification_match`.
- Produces:
  - `classify_detailed_failure(case: GoldenCase, channel_results: dict[str, list[str]], e2e_result: dict[str, Any]) -> str`
    Attributions: `alias_missing`, `embedding_text_insufficient`, `vector_recall_weak`, `rerank_issue`, `plan_error`, `adjudication_error`, `validator_error`.
  - `GoldenEvaluator.evaluate_case(case: GoldenCase, compiler_or_retrievers) -> CaseEvaluationResult`
  - `GoldenEvaluator.evaluate_dataset(dataset: list[GoldenCase]) -> DatasetEvaluationResult`

- [x] **Step 1: Write failing unit test for failure attribution and evaluation runner**

```python
# fund-screening-service/tests/unit/test_evaluation_runner.py
import pytest
from app.evaluation.schema import GoldenCase, ExpectedCondition
from app.evaluation.attribution import classify_detailed_failure, ActionableReason
from app.evaluation.runner import GoldenEvaluator


def test_failure_attribution_alias_missing():
    case = GoldenCase.model_validate({
        "id": "test-alias",
        "category": "metric",
        "query": "近三年年化",
        "expectedOutcome": "DSL",
        "expectedConditions": [
            ExpectedCondition(
                mention="近三年年化",
                domain="metric",
                relevantCandidateKeys=["f_return_3y"],
                primaryCandidateKey="f_return_3y",
            )
        ],
    })
    # Lexical and vector both missed because alias not indexed
    channel_recalls = {"pgvector": [], "lexical": [], "merged": []}
    e2e = {"dsl": None, "clarification": None}
    reason = classify_detailed_failure(case, channel_recalls, e2e)
    assert reason == ActionableReason.ALIAS_MISSING


def test_failure_attribution_plan_error():
    case = GoldenCase.model_validate({
        "id": "test-plan",
        "category": "scenario",
        "query": "规模大于10亿或者成立超过5年",
        "expectedOutcome": "DSL",
        "expectedConditions": [
            ExpectedCondition(mention="规模大于10亿", domain="metric", relevantCandidateKeys=["f_scale"]),
            ExpectedCondition(mention="成立超过5年", domain="metric", relevantCandidateKeys=["f_year"]),
        ],
    })
    channel_recalls = {"merged": ["f_scale", "f_year"]}
    # Plan error: only 1 condition extracted instead of 2
    e2e = {"plan": {"conditions": [{"mention": "规模大于10亿"}]}, "dsl": None}
    reason = classify_detailed_failure(case, channel_recalls, e2e)
    assert reason == ActionableReason.PLAN_ERROR
```

- [x] **Step 2: Run test to verify it fails**

Run: `pytest tests/unit/test_evaluation_runner.py -v`
Expected: FAIL

- [x] **Step 3: Implement `attribution.py` and `runner.py`**

Implement the 7-class failure attribution decision tree and `GoldenEvaluator` runner.

- [x] **Step 4: Run tests to verify they pass**

Run: `pytest tests/unit/test_evaluation_runner.py -v`
Expected: PASS

---

### Task 5: Report Renderer (Markdown & JSON) & CLI Evaluation Script

**Files:**
- Modify: `fund-screening-service/app/evaluation/report.py`
- Create: `fund-screening-service/scripts/evaluation/run_golden_evaluation.py`
- Test: `fund-screening-service/tests/unit/test_evaluation_report_v2.py`

**Interfaces:**
- Produces:
  - `render_markdown_report(result: DatasetEvaluationResult, metadata: dict[str, Any]) -> str`
  - `render_json_report(result: DatasetEvaluationResult, metadata: dict[str, Any]) -> dict[str, Any]`
  - CLI script: `python -m scripts.evaluation.run_golden_evaluation [--dataset-dir DIR] [--output-dir DIR]`

- [x] **Step 1: Write failing unit test for report rendering**

```python
# fund-screening-service/tests/unit/test_evaluation_report_v2.py
import pytest
from app.evaluation.report import render_markdown_report, render_json_report


def test_markdown_and_json_report_generation():
    mock_summary = {
        "dataset_version": "v1.0",
        "total_cases": 10,
        "channel_metrics": {
            "merged": {"hit@1": 0.8, "hit@3": 0.9, "mrr": 0.85, "ndcg@3": 0.88},
            "metric_pgvector": {"hit@1": 0.75, "hit@3": 0.85, "mrr": 0.80, "ndcg@3": 0.82},
        },
        "e2e_metrics": {
            "dsl_structural_match_rate": 0.80,
            "clarification_match_rate": 1.0,
        },
        "failure_attribution": {
            "alias_missing": 1,
            "vector_recall_weak": 1,
        },
        "actionable_backlog": [
            {
                "case_id": "c1",
                "reason": "alias_missing",
                "query": "近三年年化",
                "expected": "f_return_3y",
                "action": "Add alias '近三年年化' to f_return_3y in metrics catalog",
            }
        ],
    }
    md = render_markdown_report(mock_summary)
    assert "# 基金筛选 pgvector 与 DSL 金测试评估报告" in md
    assert "Hit@1" in md
    assert "alias_missing" in md

    js = render_json_report(mock_summary)
    assert js["total_cases"] == 10
    assert len(js["actionable_backlog"]) == 1
```

- [x] **Step 2: Run test to verify it fails**

Run: `pytest tests/unit/test_evaluation_report_v2.py -v`
Expected: FAIL

- [x] **Step 3: Implement `app/evaluation/report.py` and CLI script**

Implement Markdown and JSON formatting, including summary statistics, channel comparison tables, attribution breakdown, and actionable backlog recommendations.

- [x] **Step 4: Run tests to verify they pass**

Run: `pytest tests/unit/test_evaluation_report_v2.py -v`
Expected: PASS

---

### Task 6: Golden Evaluation Acceptance & Full Regression Verification

**Files:**
- Create: `fund-screening-service/tests/integration/test_golden_evaluation_pipeline.py`
- Test: `fund-screening-service/tests/integration/test_golden_evaluation_pipeline.py`

**Interfaces:**
- Executes full evaluation pipeline on the sample golden dataset and checks:
  1. All 5 golden sample files load properly.
  2. Evaluation executes across multi-channels.
  3. Channel metrics (P@K, R@K, Hit@K, MRR, nDCG) and E2E DSL/clarification metrics are computed.
  4. Failure attribution and backlog are generated.
  5. JSON and Markdown reports are produced without error.

- [x] **Step 1: Write integration test for the golden evaluation pipeline**

```python
# fund-screening-service/tests/integration/test_golden_evaluation_pipeline.py
from pathlib import Path
import pytest
from app.evaluation.loader import load_golden_dataset
from app.evaluation.runner import GoldenEvaluator
from app.evaluation.report import render_json_report, render_markdown_report


@pytest.mark.asyncio
async def test_full_golden_evaluation_pipeline():
    golden_dir = Path(__file__).resolve().parents[2] / "data" / "golden"
    cases = load_golden_dataset(golden_dir)
    assert len(cases) >= 5

    evaluator = GoldenEvaluator()
    summary = await evaluator.evaluate_dataset(cases)

    assert "channel_metrics" in summary
    assert "e2e_metrics" in summary
    assert "failure_attribution" in summary

    md_report = render_markdown_report(summary)
    assert len(md_report) > 100

    json_report = render_json_report(summary)
    assert json_report["total_cases"] == len(cases)
```

- [x] **Step 2: Run test to verify it passes**

Run: `pytest tests/integration/test_golden_evaluation_pipeline.py -v`
Expected: PASS

- [x] **Step 3: Run entire project test suite to verify zero regressions**

Run:
```powershell
& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit tests/integration tests/api -v
```
Expected: All 170+ tests PASS.

- [x] **Step 4: Verify working-tree discipline**

Run: `git status`
Expected: Clean working tree with only requested additions.
