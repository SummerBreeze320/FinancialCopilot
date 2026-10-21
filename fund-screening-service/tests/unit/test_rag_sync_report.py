# fund-screening-service/tests/unit/test_rag_sync_report.py
import pytest
from pathlib import Path
import sys

repo_root = Path(__file__).resolve().parent.parent.parent.parent
if str(repo_root) not in sys.path:
    sys.path.insert(0, str(repo_root))

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
    assert report["model_name"] == "qwen3-embedding:0.6b"
    assert report["metrics_hash"] == h
    assert report["sectors_hash"] == h
