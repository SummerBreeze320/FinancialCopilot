from dataclasses import dataclass

from app.models.dsl import FundScreeningDsl, MetricExpression, SortItem


@dataclass(frozen=True)
class ValidationResult:
    dsl: FundScreeningDsl
    repairs: tuple[str, ...] = ()
    clarification: object | None = None


@dataclass(frozen=True)
class Clarification:
    code: str
    message: str


def _normalize(expression: MetricExpression, repairs: list[str]) -> None:
    if expression.metric == "f_risk_maxdownside" and isinstance(expression.value, (int, float)) and expression.value > 0:
        expression.value = -expression.value
        expression.operator = {"<": ">=", "<=": ">", ">": "<=", ">=": "<"}.get(expression.operator, expression.operator)
        repairs.append("NEGATIVE_DRAWDOWN_STORAGE")
    for child in expression.children or []:
        _normalize(child, repairs)


def validate_and_repair(dsl: FundScreeningDsl, contracts: dict | None = None, group_limit_max: int = 1000) -> ValidationResult:
    """校验执行契约；只做不会改变投资意图的修复。"""
    result = dsl.model_copy(deep=True)
    repairs: list[str] = []
    contracts = contracts or {}
    for sort in result.sort:
        if contracts and (sort.metric not in contracts or "sort" not in contracts[sort.metric].supported_usage):
            return ValidationResult(result, tuple(repairs), Clarification("UNKNOWN_SORT_METRIC", f"无法按 {sort.metric} 排序"))
    for group in result.children:
        if group.limit and group.limit > group_limit_max:
            return ValidationResult(result, tuple(repairs), Clarification("LIMIT_TOO_LARGE", "筛选数量超过服务上限"))
        if group.limit and not group.sort:
            group.sort = [SortItem(metric="fund_code", direction="ASC")]
            repairs.append("DEFAULT_GROUP_SORT")
        _normalize(group.metric, repairs)
    return ValidationResult(result, tuple(repairs))
