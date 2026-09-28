import re
from enum import StrEnum

from app.models.dsl import FundScreeningDsl


class EditKind(StrEnum):
    NEW = "new"
    SIMPLE = "simple"
    COMPLEX = "complex"


def classify_edit(query: str, previous_dsl: dict | None) -> EditKind:
    """只将不改变条件拓扑的限额、排序和删除视为简单编辑。"""
    if previous_dsl is None:
        return EditKind.NEW
    if any(token in query for token in ("新增", "加一个", "再加", "替换", "改成指标", "或者", "排除")):
        return EditKind.COMPLEX
    if any(token in query for token in ("删除", "前", "limit", "排序", "升序", "降序")):
        return EditKind.SIMPLE
    return EditKind.COMPLEX


def apply_simple_edit(previous_dsl: FundScreeningDsl, query: str) -> FundScreeningDsl:
    match = re.search(r"前\s*(\d+)\s*只", query)
    result = previous_dsl
    if match:
        result = result.model_copy(update={"limit": int(match.group(1))})
    if "升序" in query and result.sort:
        sort = [item.model_copy(update={"direction": "ASC"}) for item in result.sort]
        result = result.model_copy(update={"sort": sort})
    if "降序" in query and result.sort:
        sort = [item.model_copy(update={"direction": "DESC"}) for item in result.sort]
        result = result.model_copy(update={"sort": sort})
    return result
