import re

from app.models.dsl import FundScreeningDsl


def apply_simple_edit(previous_dsl: FundScreeningDsl, query: str) -> FundScreeningDsl:
    match = re.search(r"前\s*(\d+)\s*只", query)
    if not match:
        return previous_dsl
    return previous_dsl.model_copy(update={"limit": int(match.group(1))})
