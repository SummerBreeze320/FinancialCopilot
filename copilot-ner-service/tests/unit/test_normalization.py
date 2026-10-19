import pytest

from app.core.normalization import normalize_alias, normalize_with_offsets


@pytest.mark.parametrize(
    ("source", "normalized", "needle", "original"),
    [
        ("请看０００００１．ＳＺ", "请看000001.sz", "000001.sz", "０００００１．ＳＺ"),
        ("比较  华夏成长  和指数", "比较 华夏成长 和指数", "华夏成长", "华夏成长"),
        ("*ST三六五怎么样", "*st三六五怎么样", "*st三六五", "*ST三六五"),
    ],
)
def test_normalization_recovers_original_spans(source, normalized, needle, original):
    result = normalize_with_offsets(source)
    assert result.text == normalized
    start = normalized.index(needle)
    span = result.original_span(start, start + len(needle))
    assert source[span.start:span.end] == original


def test_normalize_alias_collapses_whitespace_and_nfkc():
    assert normalize_alias("  华夏　成长  ") == "华夏 成长"


def test_empty_normalized_span_is_rejected():
    result = normalize_with_offsets("   ")
    assert result.text == ""
    with pytest.raises(ValueError):
        result.original_span(0, 0)
