from dataclasses import dataclass

from app.pipeline.editor import classify_edit


@dataclass(frozen=True)
class PreprocessedQuery:
    query: str
    normalized_query: str
    previous_dsl: object | None
    edit_kind: str


def preprocess(query: str, previous_dsl: object | None) -> PreprocessedQuery:
    normalized = query.replace("％", "%").replace("十亿", "10亿")
    edit_kind = classify_edit(normalized, previous_dsl if isinstance(previous_dsl, dict) else None).value
    return PreprocessedQuery(query, normalized, previous_dsl, edit_kind)
