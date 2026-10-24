from app.models.dsl import FundScreeningDsl
from app.pipeline.editor import EditKind, apply_simple_edit, classify_edit


def test_simple_edit_changes_only_final_limit() -> None:
    previous = FundScreeningDsl.model_validate({"version":"fund-screening/v1","operator":"AND","children":[{"sector":{"operator":"OR","children":[{"sectorId":"1000019220000000"}]},"metric":{"operator":"AND","children":[{"metric":"f_return_3y","operator":">","value":1,"unit":"PERCENT"}]}}],"limit":20})
    changed = apply_simple_edit(previous, "改成前50只")
    assert changed.limit == 50


def test_classifies_add_metric_as_complex_edit() -> None:
    assert classify_edit("再加一个最大回撤小于5%", previous_dsl={}) is EditKind.COMPLEX


def test_classifies_independent_request_as_new_despite_previous_dsl() -> None:
    """防止仅因携带上一轮 DSL 而误走重建分支。"""
    assert classify_edit("筛选规模大于10亿的主动权益基金", previous_dsl={}) is EditKind.NEW


def test_simple_edit_changes_final_sort_direction() -> None:
    previous = FundScreeningDsl.model_validate({"version":"fund-screening/v1","operator":"AND","children":[{"sector":{"operator":"OR","children":[{"sectorId":"1000019220000000"}]},"metric":{"operator":"AND","children":[{"metric":"f_return_3y","operator":">","value":1,"unit":"PERCENT"}]}}],"sort":[{"metric":"f_return_3y","direction":"DESC"}],"limit":20})
    changed = apply_simple_edit(previous, "排序改为升序")
    assert changed.sort[0].direction == "ASC"
