from dataclasses import dataclass

from app.models.dsl import FundScreeningDsl, MetricExpression


@dataclass(frozen=True)
class ValidationResult:
    dsl: FundScreeningDsl
    repairs: tuple[str, ...] = ()
    clarification: object | None = None


def _normalize(expression: MetricExpression, repairs: list[str]) -> None:
    if expression.metric == "f_risk_maxdownside" and isinstance(expression.value, (int, float)) and expression.value > 0:
        expression.value = -expression.value
        expression.operator = {"<": ">=", "<=": ">", ">": "<=", ">=": "<"}.get(expression.operator, expression.operator)
        repairs.append("NEGATIVE_DRAWDOWN_STORAGE")
    for child in expression.children or []:
        _normalize(child, repairs)


def validate_and_repair(dsl: FundScreeningDsl) -> ValidationResult:
    result = dsl.model_copy(deep=True)
    repairs: list[str] = []
    for group in result.children:
        _normalize(group.metric, repairs)
    return ValidationResult(result, tuple(repairs))
