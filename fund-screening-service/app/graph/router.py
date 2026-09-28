from __future__ import annotations

import re
from typing import Literal

from app.graph.state import FundScreeningState


def classify_screening_route(state: FundScreeningState) -> Literal["direct", "simple", "full"]:
    query = state.get("normalized_query") or state.get("query", "")

    # 1. 复杂逻辑判定 (OR / NOT / 括号嵌套) -> full
    if any(k in query for k in ("或者", "或", "OR", "排除", "不是", "NOT", "且（", "且(")):
        return "full"

    # 2. 多业务组分隔符判定 -> full
    if len(re.split(r"[;；]", query)) > 1:
        return "full"

    # 3. 候选池已有单高置信候选且参数完整 -> direct
    pool = state.get("candidate_pool", [])
    if len(pool) == 1 and pool[0].get("score", 0.0) >= 0.95:
        return "direct"

    # 4. 简单单组且少于 4 个 AND 条件 -> simple
    conditions = [c for c in re.split(r"[且与和、,，\s]+", query) if c.strip()]
    if len(conditions) <= 3:
        return "simple"

    return "full"
