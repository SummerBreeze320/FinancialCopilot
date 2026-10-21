import pytest
from pydantic import ValidationError

from app.models.dsl_nodes import MetricPredicate, MetricLogicNode
from app.models.dsl import SectorLeaf


def test_logic_node_has_no_leaf_fields():
    node = MetricLogicNode(
        operator="AND",
        children=[MetricPredicate(metric="f_scale", operator=">", value=10, unit="元")],
    )
    dumped = node.model_dump(exclude_none=True)
    assert dumped == {
        "operator": "AND",
        "children": [{"metric": "f_scale", "operator": ">", "value": 10, "unit": "元", "params": []}],
    }
    assert "params" not in dumped
    assert "metric" not in dumped
    assert "value" not in dumped
    assert "unit" not in dumped


def test_metric_predicate_rejects_missing_value():
    with pytest.raises((ValidationError, ValueError)):
        MetricPredicate(metric="f_scale", operator=">")


def test_sector_leaf_only_contains_sector_id():
    leaf = SectorLeaf(sectorId="1000019220000000")
    dumped = leaf.model_dump(by_alias=True)
    assert dumped == {"sectorId": "1000019220000000"}
    assert "match" not in dumped


def test_metric_logic_node_rejects_not_with_multiple_children():
    with pytest.raises((ValidationError, ValueError)):
        MetricLogicNode(
            operator="NOT",
            children=[
                MetricPredicate(metric="m1", operator=">", value=1),
                MetricPredicate(metric="m2", operator=">", value=2),
            ],
        )
