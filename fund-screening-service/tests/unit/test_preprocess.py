from app.pipeline.preprocess import preprocess


def test_preprocess_normalizes_chinese_amount_and_percent() -> None:
    result = preprocess("规模大于十亿，回撤低于15％", None)
    assert "10亿" in result.normalized_query
    assert "15%" in result.normalized_query
