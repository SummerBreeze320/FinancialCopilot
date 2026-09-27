from app.catalog.models import CatalogRecord
from app.core.resolver import Resolver, ScoredCandidate
from app.models.api import Category, MatchMethod, Span


def scored(entity_id: str, category: Category, confidence: float) -> ScoredCandidate:
    value = entity_id.split(":", 1)[1]
    return ScoredCandidate(
        entity=CatalogRecord(
            id=entity_id,
            category=category,
            canonical_value=value,
            aliases=(value,),
            normalized_value=value.casefold(),
            normalized_aliases=(value.casefold(),),
            metadata={},
            search_text=value,
        ),
        confidence=confidence,
        method=MatchMethod.HYBRID,
    )


def resolver() -> Resolver:
    return Resolver(return_min_confidence=0.55, resolved_min_confidence=0.86,
                    resolved_min_margin=0.10, ambiguous_max_candidates=5)


def test_close_cross_category_candidates_are_ambiguous():
    result = resolver().resolve("000001", Span(start=0, end=6), [
        scored("fund_code:000001.OF", Category.FUND_CODE, 0.91),
        scored("stock_code:000001.SZ", Category.STOCK_CODE, 0.88),
        scored("track_index_code:000001.SH", Category.TRACK_INDEX_CODE, 0.86),
    ], context="比较000001最近表现")
    assert result.resolution == "ambiguous"
    assert [candidate.entity.category for candidate in result.candidates] == [
        Category.FUND_CODE, Category.STOCK_CODE, Category.TRACK_INDEX_CODE
    ]


def test_dominant_candidate_resolves():
    result = resolver().resolve("000001", Span(start=0, end=6), [
        scored("fund_code:000001.OF", Category.FUND_CODE, 0.96),
        scored("stock_code:000001.SZ", Category.STOCK_CODE, 0.70),
    ], context="000001基金表现")
    assert result.resolution == "resolved"
    assert result.entity.id == "fund_code:000001.OF"


def test_candidates_below_return_threshold_are_removed():
    result = resolver().resolve("平银", Span(start=0, end=2), [
        scored("stock_name:平安银行", Category.STOCK_NAME, 0.51),
    ], context="平银")
    assert result is None


def test_duplicate_entity_keeps_highest_score():
    result = resolver().resolve("平银", Span(start=0, end=2), [
        scored("stock_name:平安银行", Category.STOCK_NAME, 0.90),
        scored("stock_name:平安银行", Category.STOCK_NAME, 0.95),
    ], context="平银股票")
    assert result.resolution == "resolved"
    assert result.match.confidence == 0.95
