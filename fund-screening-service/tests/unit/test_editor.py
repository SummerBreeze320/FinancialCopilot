from app.models.dsl import FundScreeningDsl
from app.pipeline.editor import apply_simple_edit


def test_simple_edit_changes_only_final_limit() -> None:
    previous = FundScreeningDsl.model_validate({"version":"fund-screening/v1","operator":"AND","children":[{"sector":{"operator":"OR","children":[{"sectorId":"1000019220000000"}]},"metric":{"operator":"AND","children":[{"metric":"f_return_3y","operator":">","value":1,"unit":"PERCENT"}]}}],"limit":20})
    changed = apply_simple_edit(previous, "改成前50只")
    assert changed.limit == 50
