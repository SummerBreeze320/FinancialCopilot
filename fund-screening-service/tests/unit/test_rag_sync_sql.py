# fund-screening-service/tests/unit/test_rag_sync_sql.py
import pytest
from unittest.mock import MagicMock
from pathlib import Path
import sys

repo_root = Path(__file__).resolve().parent.parent.parent.parent
if str(repo_root) not in sys.path:
    sys.path.insert(0, str(repo_root))

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


def test_sync_sectors_upsert_sql_params():
    rec = SectorRecord(
        sector_id="s1",
        parent_id=None,
        name="内地公募基金",
        name_en="CN Funds",
        aliases=["内地基金"],
        description="根分类",
        embedding_text="内地公募基金",
        is_leaf=False,
        element_type=6,
        tree_level=0,
        full_path_names="内地公募基金",
        enabled=True,
    )
    vec = [0.0] * 1024
    mock_conn = MagicMock()
    mock_cursor = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cursor
    mock_cursor.fetchall.return_value = [("s1",)]  # Already exists

    inserted, updated = sync_sectors_to_db(mock_conn, [rec], [vec], dry_run=False)
    assert inserted == 0
    assert updated == 1
    assert mock_cursor.executemany.called
    args = mock_cursor.executemany.call_args[0]
    sql, params_list = args[0], args[1]
    assert "ON CONFLICT (sector_id) DO UPDATE" in sql
    assert len(params_list[0]) == 13
