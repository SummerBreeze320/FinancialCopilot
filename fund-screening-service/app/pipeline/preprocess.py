from dataclasses import dataclass


@dataclass(frozen=True)
class PreprocessedQuery:
    query: str
    normalized_query: str
    previous_dsl: object | None
    edit_kind: str


def preprocess(query: str, previous_dsl: object | None) -> PreprocessedQuery:
    normalized = query.replace("％", "%").replace("十亿", "10亿")
    edit_kind = "simple" if previous_dsl and any(word in normalized for word in ("前", "limit", "排序", "升序", "降序")) else "new"
    return PreprocessedQuery(query, normalized, previous_dsl, edit_kind)
