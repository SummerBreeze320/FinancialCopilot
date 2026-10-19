from app.core.mention_generator import MentionGenerator
from app.core.normalization import normalize_with_offsets
from app.models.api import Span


def mentions(text: str, covered: list[Span] | None = None) -> list[str]:
    normalized = normalize_with_offsets(text)
    return [item.mention for item in MentionGenerator(max_spans=128).generate(normalized, covered or [])]


def test_generates_financial_name_and_code_candidates():
    result = mentions("比较华夏沪深三百、平银和000001SZ")
    assert "华夏沪深三百" in result
    assert "平银" in result
    assert "000001SZ" in result


def test_skips_punctuation_only_and_covered_spans():
    text = "华夏成长，平银"
    result = mentions(text, [Span(start=0, end=4)])
    assert "华夏成长" not in result
    assert "，" not in result
    assert "平银" in result


def test_respects_maximum_span_count():
    normalized = normalize_with_offsets("华夏成长平安银行沪深三百测试转债")
    result = MentionGenerator(max_spans=3).generate(normalized, [])
    assert len(result) == 3
