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

    clean_q = query.strip()
    # 3. 纯基金代码判定 (如 001938, 001938.OF, 161725) -> direct
    if re.fullmatch(r"\d{6}(\.[A-Za-z]+)?", clean_q):
        return "direct"

    # 4. 候选池有精确匹配实体且 query 无过滤操作符 -> direct
    has_operator = any(op in clean_q for op in (">", "<", "=", "大于", "小于", "超过", "低于", "高于"))
    if not has_operator:
        pool = state.get("candidate_pool") or []
        for c in pool:
            if c.get("domain") == "entity" and c.get("score", 0.0) >= 0.95:
                name = c.get("name", "")
                aliases = c.get("aliases", [])
                if clean_q == name or clean_q in aliases or not name:
                    return "direct"

    # 5. 简单单组且少于 4 个 AND 条件 -> simple
    conditions = [c for c in re.split(r"[且与和、,，\s]+", query) if c.strip()]
    if len(conditions) <= 3:
        return "simple"

    return "full"
