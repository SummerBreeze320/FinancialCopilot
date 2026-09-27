from pathlib import Path

from app.catalog.loader import CatalogLoader
from app.core.exact_matcher import ExactMatcher
from app.core.normalization import normalize_with_offsets
from app.models.api import Category


FIXTURE = Path(__file__).parents[1] / "fixtures" / "entity-small.json"


def matcher() -> ExactMatcher:
    return ExactMatcher.build(CatalogLoader().load(FIXTURE).entities)


def test_shared_alias_keeps_every_category():
    hits = matcher().find(normalize_with_offsets("比较000001最近表现"))
    group = [hit for hit in hits if hit.mention == "000001"]
    assert {hit.entity.category for hit in group} == {
        Category.FUND_CODE,
        Category.STOCK_CODE,
        Category.TRACK_INDEX_CODE,
    }


def test_code_does_not_match_inside_longer_number():
    hits = matcher().find(normalize_with_offsets("订单号10000012不是证券代码"))
    assert not any(hit.mention == "000001" for hit in hits)


def test_original_case_and_width_are_preserved_in_mention():
    hits = matcher().find(normalize_with_offsets("请看０００００１．ＳＺ走势"))
    stock = next(hit for hit in hits if hit.entity.id == "stock_code:000001.SZ")
    assert stock.mention == "０００００１．ＳＺ"
    assert (stock.span.start, stock.span.end) == (2, 11)


def test_identical_hit_is_not_duplicated():
    hits = matcher().find(normalize_with_offsets("华夏成长"))
    keys = {(hit.span.start, hit.span.end, hit.entity.id) for hit in hits}
    assert len(keys) == len(hits)
