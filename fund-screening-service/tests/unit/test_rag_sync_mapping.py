# fund-screening-service/tests/unit/test_rag_sync_mapping.py
import json
import pytest
from pathlib import Path
import sys

# Ensure repository root is in sys.path so scripts.data_sync can be imported
repo_root = Path(__file__).resolve().parent.parent.parent.parent
if str(repo_root) not in sys.path:
    sys.path.insert(0, str(repo_root))

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


def test_metric_embedding_text_generation():
    text = generate_metric_embedding_text({
        "mnemonic": "f_nav_adjustedreturn",
        "index_name": "复权单位净值增长率",
        "description": "指定区间收益",
        "embedding_text": "复权净值增长 区间收益",
        "aliases": ["区间回报", "指定日期区间收益"],
        "parameters": [{"key": "windCode"}, {"key": "startDate"}, {"key": "endDate"}],
        "result": {"valueType": "DECIMAL", "unit": "%"},
    })
    assert "复权净值增长" in text
    assert "区间回报" in text
    assert "指定日期区间收益" in text
    assert "运算符:" not in text
    assert "用法:" not in text


def test_catalog_alias_hardening():
    repo_root = Path(__file__).resolve().parent.parent.parent.parent
    metrics_path = repo_root / "docs" / "temp" / "metrics.json"
    sectors_path = repo_root / "docs" / "temp" / "sectors.json"

    metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
    metrics_by_key = {m["mnemonic"]: m for m in metrics}
    assert "f_nav_adjustedreturn" in metrics_by_key
    assert "区间收益" in (metrics_by_key["f_nav_adjustedreturn"].get("aliases") or [])
    assert "季度收益" in (metrics_by_key["f_nav_adjustedreturn"].get("aliases") or [])

    assert "f_netasset_total" in metrics_by_key
    assert "合并规模" in (metrics_by_key["f_netasset_total"].get("aliases") or [])
    assert "规模合计" in (metrics_by_key["f_netasset_total"].get("aliases") or [])

    sectors = json.loads(sectors_path.read_text(encoding="utf-8"))
    sectors_by_id = {s.get("source_sector_id") or s.get("sector_id"): s for s in sectors}
    assert "1000002540000000" in sectors_by_id
    assert "ETF" in (sectors_by_id["1000002540000000"].get("aliases") or [])
    assert "交易型开放式指数基金" in (sectors_by_id["1000002540000000"].get("aliases") or [])
