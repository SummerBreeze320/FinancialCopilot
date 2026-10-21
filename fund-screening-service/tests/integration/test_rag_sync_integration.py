# fund-screening-service/tests/integration/test_rag_sync_integration.py
import os
import pytest
from pathlib import Path
import sys

repo_root = Path(__file__).resolve().parent.parent.parent.parent
if str(repo_root) not in sys.path:
    sys.path.insert(0, str(repo_root))

from scripts.data_sync.sync_fund_screening_rag import run_rag_sync


@pytest.mark.skipif(
    os.getenv("RUN_RAG_SYNC_INTEGRATION") != "true",
    reason="Set RUN_RAG_SYNC_INTEGRATION=true to run live sync integration test",
)
def test_live_rag_sync_acceptance():
    metrics_path = repo_root / "docs" / "temp" / "metrics.json"
    sectors_path = repo_root / "docs" / "temp" / "sectors.json"
    report_path = repo_root / "docs" / "temp" / "rag_sync_test_report.json"

    report = run_rag_sync(
        metrics_path=str(metrics_path),
        sectors_path=str(sectors_path),
        database_url="postgresql://postgres:123456@127.0.0.1:15432/financial_copilot",
        embedding_base_url="http://127.0.0.1:11434",
        embedding_model="qwen3-embedding:0.6b",
        batch_size=32,
        report_path=str(report_path),
        dry_run=False,
    )
    assert report["verification"]["passed"] is True
    assert report["verification"]["metrics_count"] == 168
    assert report["verification"]["sectors_count"] == 1712
    assert report["verification"]["metrics_vector_dims"] == [1024]
    assert report["verification"]["sectors_vector_dims"] == [1024]
