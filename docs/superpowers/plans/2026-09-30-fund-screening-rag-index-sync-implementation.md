# Fund Screening RAG Index Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Idempotently sync `docs/temp/metrics.json` and `docs/temp/sectors.json` into local PostgreSQL `rag_fund_metric` and `rag_fund_sector` tables with 1024-dimensional vectors from local Ollama `qwen3-embedding:0.6b`, verified by automated counts, vector dimensions, Top-K queries, and an auditable JSON report.

**Architecture:** A standalone Python sync CLI `scripts/data_sync/sync_fund_screening_rag.py` ingests catalog JSONs, computes sector tree hierarchies and rich semantic embedding texts, batches requests to the Ollama OpenAI-compatible embedding endpoint (`/v1/embeddings`), performs transactional batch PostgreSQL UPSERTs with strict 1024-dim validation, runs post-sync SQL audits, and emits a structured verification report.

**Tech Stack:** Python 3.11, `psycopg2-binary`, standard library `urllib` / `dataclasses` / `json` / `hashlib`, PostgreSQL 16 + pgvector, Ollama `qwen3-embedding:0.6b`.

**Spec:** [docs/superpowers/specs/2026-09-30-fund-screening-rag-index-sync-design.md](file:///d:/BaiduSyncdisk/IdeaProjects/FinancialCopilot/.worktrees/fund-screening-agent/docs/superpowers/specs/2026-09-30-fund-screening-rag-index-sync-design.md)

## Global Constraints

- Source catalogs: `docs/temp/metrics.json` and `docs/temp/sectors.json` are authoritative; do not alter other database tables.
- Vector dimension: Must be strictly 1024 dimensions (`vector(1024)`); reject and rollback any batch with mismatched dimensions.
- Idempotency: Synchronizing twice must produce identical row counts and vector contents.
- Parameter and result contracts: Mapped verbatim from the catalog without heuristic rewriting.
- Security: Never log or embed user data, passwords, conversation data, or sensitive tokens.

---

### Task 1: Catalog Ingestion, Hierarchy Resolution & Embedding Text Models

**Files:**
- Create: `scripts/data_sync/sync_fund_screening_rag.py`
- Create: `fund-screening-service/tests/unit/test_rag_sync_mapping.py`

**Interfaces:**
- `load_metrics_catalog(path: str | Path) -> list[MetricRecord]`
  - Filters `enabled == True`.
  - Maps `mnemonic`, `index_name`, `parent_name`, `description`, `supported_usage`, `applicable_products`, `aliases`, `version`, `parameters`, `result_contract`, `parameter_schema_version`, `contract_status`.
  - Produces rich metric `embedding_text` containing: index_name, parent_name, description, aliases, supported usages, parameter names, and result contract summary.
- `load_sectors_catalog(path: str | Path) -> list[SectorRecord]`
  - Filters `enabled == True`.
  - Builds full sector tree from `parent_id` relations; computes `tree_level` (int) and `full_path_names` (e.g. `'内地公募基金 > 基金市场类 > 开放式基金'`).
  - Produces rich sector `embedding_text` containing: name, aliases, description, full path, tree level, and leaf/branch node type.

- [x] **Step 1: Write failing unit test for catalog loading, hierarchy resolution, and embedding text**

```python
# fund-screening-service/tests/unit/test_rag_sync_mapping.py
import json
import pytest
from pathlib import Path
from scripts.data_sync.sync_fund_screening_rag import (
    load_metrics_catalog,
    load_sectors_catalog,
    generate_metric_embedding_text,
    generate_sector_embedding_text,
)


def test_load_metrics_catalog_filters_disabled_and_builds_fields(tmp_path):
    catalog_data = [
        {
            "mnemonic": "f_return_1y",
            "index_name": "近1年回报",
            "parent_name": "收益指标",
            "description": "近1年回报率",
            "aliases": ["近一年收益"],
            "supported_usage": ["filter", "sort"],
            "enabled": True,
            "parameters": [{"position": 1, "key": "windCode", "valueType": "STRING"}],
            "result": {"valueType": "PERCENT", "supportedOperators": [">", "<="]},
        },
        {
            "mnemonic": "f_disabled",
            "index_name": "停用指标",
            "parent_name": "测试",
            "enabled": False,
        },
    ]
    p = tmp_path / "metrics.json"
    p.write_text(json.dumps(catalog_data, ensure_ascii=False), encoding="utf-8")

    records = load_metrics_catalog(p)
    assert len(records) == 1
    rec = records[0]
    assert rec.mnemonic == "f_return_1y"
    assert "近1年回报" in rec.embedding_text
    assert "近一年收益" in rec.embedding_text
    assert "windCode" in rec.embedding_text


def test_load_sectors_catalog_computes_hierarchy(tmp_path):
    sectors_data = [
        {
            "source_sector_id": "root_1",
            "name": "内地公募基金",
            "parent_id": None,
            "is_leaf": False,
            "enabled": True,
        },
        {
            "source_sector_id": "child_1",
            "name": "股票型基金",
            "parent_id": "root_1",
            "is_leaf": True,
            "aliases": ["股基"],
            "description": "股票型分类",
            "enabled": True,
        },
    ]
    p = tmp_path / "sectors.json"
    p.write_text(json.dumps(sectors_data, ensure_ascii=False), encoding="utf-8")

    records = load_sectors_catalog(p)
    assert len(records) == 2
    by_id = {r.sector_id: r for r in records}
    assert by_id["root_1"].tree_level == 0
    assert by_id["root_1"].full_path_names == "内地公募基金"
    assert by_id["child_1"].tree_level == 1
    assert by_id["child_1"].full_path_names == "内地公募基金 > 股票型基金"
    assert "股票型基金" in by_id["child_1"].embedding_text
    assert "股基" in by_id["child_1"].embedding_text
    assert "层级:1" in by_id["child_1"].embedding_text
```

- [x] **Step 2: Run test to verify it fails**

Run: `pytest tests/unit/test_rag_sync_mapping.py -v`
Expected: FAIL (module not yet implemented)

- [x] **Step 3: Implement data models and loaders in `scripts/data_sync/sync_fund_screening_rag.py`**

Implement:
- `MetricRecord` and `SectorRecord` dataclasses.
- `generate_metric_embedding_text(item: dict) -> str`
- `generate_sector_embedding_text(item: dict, full_path: str, tree_level: int) -> str`
- `load_metrics_catalog(path: str | Path) -> list[MetricRecord]`
- `load_sectors_catalog(path: str | Path) -> list[SectorRecord]`

- [x] **Step 4: Run test to verify it passes**

Run: `pytest tests/unit/test_rag_sync_mapping.py -v`
Expected: PASS

---

### Task 2: 1024-Dim Batch Embedding Client & Error Guarding

**Files:**
- Modify: `scripts/data_sync/sync_fund_screening_rag.py`
- Create: `fund-screening-service/tests/unit/test_rag_sync_embedding.py`

**Interfaces:**
- `EmbeddingClient(base_url: str, model_name: str, timeout: float = 30.0)`
  - `embed_batch(texts: list[str]) -> list[list[float]]`
  - Validates dimension of every vector == 1024.
  - Raises `ValueError(f"Expected 1024 dimensions, got {len(vec)}")` on mismatch.
  - Batches calls efficiently.

- [x] **Step 1: Write failing unit test for batch embedding client & 1024-dim enforcement**

```python
# fund-screening-service/tests/unit/test_rag_sync_embedding.py
import json
import pytest
from unittest.mock import patch, MagicMock
from scripts.data_sync.sync_fund_screening_rag import EmbeddingClient


def test_embedding_client_success_1024_dims():
    client = EmbeddingClient(base_url="http://127.0.0.1:11434", model_name="qwen3-embedding:0.6b")
    mock_resp = {
        "data": [
            {"embedding": [0.1] * 1024},
            {"embedding": [0.2] * 1024},
        ]
    }
    with patch("urllib.request.urlopen") as mock_url:
        mock_ctx = MagicMock()
        mock_ctx.read.return_value = json.dumps(mock_resp).encode("utf-8")
        mock_ctx.__enter__.return_value = mock_ctx
        mock_url.return_value = mock_ctx

        vectors = client.embed_batch(["text 1", "text 2"])
        assert len(vectors) == 2
        assert len(vectors[0]) == 1024


def test_embedding_client_rejects_non_1024_dims():
    client = EmbeddingClient(base_url="http://127.0.0.1:11434", model_name="qwen3-embedding:0.6b")
    mock_resp = {
        "data": [
            {"embedding": [0.1] * 768},  # wrong dims
        ]
    }
    with patch("urllib.request.urlopen") as mock_url:
        mock_ctx = MagicMock()
        mock_ctx.read.return_value = json.dumps(mock_resp).encode("utf-8")
        mock_ctx.__enter__.return_value = mock_ctx
        mock_url.return_value = mock_ctx

        with pytest.raises(ValueError, match="Expected 1024 dimensions"):
            client.embed_batch(["text 1"])
```

- [x] **Step 2: Run test to verify it fails**

Run: `pytest tests/unit/test_rag_sync_embedding.py -v`
Expected: FAIL

- [x] **Step 3: Implement `EmbeddingClient` in `scripts/data_sync/sync_fund_screening_rag.py`**

Implement `EmbeddingClient` using `urllib.request` with OpenAI-compatible `/v1/embeddings` endpoint. Add strict 1024-dim check.

- [x] **Step 4: Run test to verify it passes**

Run: `pytest tests/unit/test_rag_sync_embedding.py -v`
Expected: PASS

---

### Task 3: Idempotent PostgreSQL Upsert Engine & Transaction Safety

**Files:**
- Modify: `scripts/data_sync/sync_fund_screening_rag.py`
- Create: `fund-screening-service/tests/unit/test_rag_sync_sql.py`

**Interfaces:**
- `sync_metrics_to_db(connection, records: list[MetricRecord], vectors: list[list[float]], dry_run: bool = False) -> tuple[int, int]`
  - Upserts into `rag_fund_metric` using `ON CONFLICT (mnemonic) DO UPDATE`.
  - Returns `(inserted_count, updated_count)`.
- `sync_sectors_to_db(connection, records: list[SectorRecord], vectors: list[list[float]], dry_run: bool = False) -> tuple[int, int]`
  - Upserts into `rag_fund_sector` using `ON CONFLICT (sector_id) DO UPDATE`.
  - Returns `(inserted_count, updated_count)`.

- [x] **Step 1: Write failing unit test for SQL upsert generation and parameter bindings**

```python
# fund-screening-service/tests/unit/test_rag_sync_sql.py
import pytest
from unittest.mock import MagicMock
from scripts.data_sync.sync_fund_screening_rag import (
    sync_metrics_to_db,
    sync_sectors_to_db,
    MetricRecord,
    SectorRecord,
)


def test_sync_metrics_upsert_sql_params():
    rec = MetricRecord(
        mnemonic="f_scale",
        index_name="基金规模",
        parent_name="通用指标",
        description="规模",
        embedding_text="基金规模",
        source_indicator_id=123,
        supported_usage=["filter"],
        applicable_products="1001",
        aliases=["规模"],
        version=1,
        enabled=True,
        parameters=[{"key": "windCode"}],
        result_contract={"valueType": "DECIMAL"},
        parameter_schema_version=1,
        contract_status="REVIEWED",
    )
    vec = [0.0] * 1024
    mock_conn = MagicMock()
    mock_cursor = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cursor
    mock_cursor.fetchall.return_value = []

    inserted, updated = sync_metrics_to_db(mock_conn, [rec], [vec], dry_run=False)
    assert inserted == 1
    assert updated == 0
    assert mock_cursor.executemany.called
    args = mock_cursor.executemany.call_args[0]
    sql, params_list = args[0], args[1]
    assert "ON CONFLICT (mnemonic) DO UPDATE" in sql
    assert len(params_list[0]) == 16
```

- [x] **Step 2: Run test to verify it fails**

Run: `pytest tests/unit/test_rag_sync_sql.py -v`
Expected: FAIL

- [x] **Step 3: Implement upsert logic with transaction handling**

Add `sync_metrics_to_db` and `sync_sectors_to_db` using `executemany` or batch queries with pgvector string formatting (`'[' + ','.join(map(str, vec)) + ']'`).

- [x] **Step 4: Run test to verify it passes**

Run: `pytest tests/unit/test_rag_sync_sql.py -v`
Expected: PASS

---

### Task 4: Post-Sync Verification Suite & Auditable JSON Reporting

**Files:**
- Modify: `scripts/data_sync/sync_fund_screening_rag.py`
- Create: `fund-screening-service/tests/unit/test_rag_sync_report.py`

**Interfaces:**
- `verify_sync_integrity(connection, embedding_client) -> SyncVerificationResult`
  - Runs count queries: `count(*)`, `count(embedding)`.
  - Runs dimension queries: `vector_dims(embedding)`.
  - Runs fixed Top-K semantic queries against `rag_fund_metric` and `rag_fund_sector`.
- `build_sync_report(...) -> dict`
  - Calculates file SHA-256 hashes for `metrics.json` and `sectors.json`.
  - Emits JSON with metadata, stats, checks, and timings.

- [x] **Step 1: Write failing unit test for audit report builder**

```python
# fund-screening-service/tests/unit/test_rag_sync_report.py
import pytest
from scripts.data_sync.sync_fund_screening_rag import build_sync_report, compute_file_sha256


def test_build_sync_report_contains_hashes_and_stats(tmp_path):
    f = tmp_path / "test.json"
    f.write_text("{}", encoding="utf-8")
    h = compute_file_sha256(f)
    assert len(h) == 64

    report = build_sync_report(
        metrics_file=str(f),
        sectors_file=str(f),
        metrics_hash=h,
        sectors_hash=h,
        model_name="qwen3-embedding:0.6b",
        batch_size=32,
        metrics_stats={"total": 1, "inserted": 1, "updated": 0},
        sectors_stats={"total": 1, "inserted": 1, "updated": 0},
        verification={"passed": True, "metric_count": 1, "sector_count": 1},
        elapsed_seconds=1.23,
        dry_run=False,
    )
    assert report["embedding_dims"] == 1024
    assert report["verification"]["passed"] is True
```

- [x] **Step 2: Run test to verify it fails**

Run: `pytest tests/unit/test_rag_sync_report.py -v`
Expected: FAIL

- [x] **Step 3: Implement verification and report generation**

Add `compute_file_sha256`, `verify_sync_integrity`, and `build_sync_report` to `scripts/data_sync/sync_fund_screening_rag.py`.

- [x] **Step 4: Run test to verify it passes**

Run: `pytest tests/unit/test_rag_sync_report.py -v`
Expected: PASS

---

### Task 5: End-to-End CLI Pipeline & Live Integration Acceptance

**Files:**
- Modify: `scripts/data_sync/sync_fund_screening_rag.py`
- Create: `fund-screening-service/tests/integration/test_rag_sync_integration.py`

**Interfaces:**
- CLI entrypoint `main()`:
  - `--metrics`: path to `metrics.json`
  - `--sectors`: path to `sectors.json`
  - `--database-url`: PostgreSQL URL (defaults to `postgresql://postgres:123456@127.0.0.1:15432/financial_copilot`)
  - `--embedding-base-url`: Ollama URL (defaults to `http://127.0.0.1:11434`)
  - `--embedding-model`: embedding model (defaults to `qwen3-embedding:0.6b`)
  - `--batch-size`: batch size (defaults to 32)
  - `--report`: path for report output (defaults to `docs/temp/rag_sync_report.json`)
  - `--dry-run`: if set, does not commit DB changes

- [x] **Step 1: Write integration test for live/mock DB and Ollama sync**

```python
# fund-screening-service/tests/integration/test_rag_sync_integration.py
import os
import pytest
from scripts.data_sync.sync_fund_screening_rag import run_rag_sync

@pytest.mark.skipif(
    os.getenv("RUN_RAG_SYNC_INTEGRATION") != "true",
    reason="Set RUN_RAG_SYNC_INTEGRATION=true to run live sync integration test",
)
def test_live_rag_sync_acceptance():
    report = run_rag_sync(
        metrics_path="docs/temp/metrics.json",
        sectors_path="docs/temp/sectors.json",
        database_url="postgresql://postgres:123456@127.0.0.1:15432/financial_copilot",
        embedding_base_url="http://127.0.0.1:11434",
        embedding_model="qwen3-embedding:0.6b",
        batch_size=32,
        dry_run=False,
    )
    assert report["verification"]["passed"] is True
    assert report["verification"]["metrics_count"] == 168
    assert report["verification"]["sectors_count"] == 1712
    assert report["verification"]["metrics_vector_dims"] == [1024]
    assert report["verification"]["sectors_vector_dims"] == [1024]
```

- [x] **Step 2: Implement CLI `main()` and `run_rag_sync`**

Wire the complete workflow in `scripts/data_sync/sync_fund_screening_rag.py`.

- [x] **Step 3: Run full unit test suite**

Run:
```powershell
& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit/test_rag_sync_*.py -v
```
Expected: PASS

- [x] **Step 4: Execute live sync and verify all 5 acceptance criteria**

Run:
```powershell
& "D:\Program Files\anaconda3\envs\python311\python.exe" scripts/data_sync/sync_fund_screening_rag.py --report docs/temp/rag_sync_report.json
```
Verify:
1. Metric count == 168, sector count == 1712.
2. `count(embedding) == count(*)`, `vector_dims == 1024`.
3. Re-run command: 0 added rows.
4. Top-K search returns valid hits.
5. Report written to `docs/temp/rag_sync_report.json`.

- [x] **Step 5: Run full project regression tests**

Run:
```powershell
& "D:\Program Files\anaconda3\envs\python311\python.exe" -m pytest tests/unit tests/integration tests/api -v
```
Expected: All existing tests continue to PASS.
