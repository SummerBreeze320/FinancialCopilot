import pytest
from app.models.dsl import FundScreeningDsl, BusinessGroup, SectorExpression, SectorLeaf, SortItem
from app.models.dsl_nodes import MetricLogicNode, MetricPredicate
from app.pipeline.editor import apply_simple_edit, classify_edit, EditKind


@pytest.fixture
def sample_dsl():
    return FundScreeningDsl(
        version="fund-screening/v1",
        operator="AND",
        children=[
            BusinessGroup(
                sector=SectorExpression(operator="OR", children=[SectorLeaf(sectorId="1000019220000000")]),
                metric=MetricLogicNode(
                    operator="AND",
                    children=[
                        MetricPredicate(metric="f_info_fundscale", operator=">", value=10.0, unit="亿"),
                        MetricPredicate(metric="f_info_existingyear", operator=">", value=3.0, unit="年"),
                    ],
                ),
                sort=[SortItem(metric="f_info_fundscale", direction="DESC")],
                limit=20,
            )
        ],
        sort=[SortItem(metric="f_info_fundscale", direction="DESC")],
        limit=20,
    )


def test_multi_round_edit_limit_preserves_ast(sample_dsl):
    edited = apply_simple_edit(sample_dsl, "改成前50只")
    assert edited.limit == 50
    assert edited.children[0].limit == 50
    # Metrics preserved
    assert len(edited.children[0].metric.children) == 2
    assert edited.children[0].metric.children[0].value == 10.0


def test_multi_round_edit_sort_direction(sample_dsl):
    edited = apply_simple_edit(sample_dsl, "按规模改为升序")
    assert edited.sort[0].direction == "ASC"
    assert edited.children[0].sort[0].direction == "ASC"


def test_multi_round_edit_remove_condition(sample_dsl):
    # Removing "规模" condition should delete the f_info_fundscale metric leaf
    edited = apply_simple_edit(sample_dsl, "去掉规模限制")
    assert len(edited.children[0].metric.children) == 1
    assert edited.children[0].metric.children[0].metric == "f_info_existingyear"


def test_multi_round_classify_edit_detection(sample_dsl):
    previous_dsl_dict = sample_dsl.model_dump(by_alias=True, exclude_none=True)
    
    # Simple limit edit
    assert classify_edit("改成前100只", previous_dsl_dict) == EditKind.SIMPLE
    
    # Simple sort edit
    assert classify_edit("改为升序", previous_dsl_dict) == EditKind.SIMPLE
    
    # Simple unique removal edit
    assert classify_edit("去掉规模限制", previous_dsl_dict) == EditKind.SIMPLE
    
    # Complex edit (adding new condition)
    assert classify_edit("再加一个收益大于10%", previous_dsl_dict) == EditKind.COMPLEX
