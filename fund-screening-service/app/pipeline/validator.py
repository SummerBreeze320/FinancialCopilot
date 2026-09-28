from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from app.models.dsl import FundScreeningDsl, MetricExpression, SortItem
from app.models.parameters import MetricParameterDefinition


@dataclass(frozen=True)
class ValidationResult:
    dsl: FundScreeningDsl
    repairs: tuple[str, ...] = ()
    clarification: Clarification | None = None


@dataclass(frozen=True)
class Clarification:
    code: str
    message: str


def validate_metric_parameters(
    metric_name: str,
    input_params: list[dict[str, Any]],
    param_defs: list[MetricParameterDefinition] | tuple[MetricParameterDefinition, ...],
) -> tuple[list[dict[str, str]], list[dict[str, Any]], list[str]]:
    errors: list[dict[str, str]] = []
    repairs: list[str] = []
    param_map = {p["key"]: p["value"] for p in input_params if "key" in p}

    for pdef in param_defs:
        key = pdef.key
        vtype = pdef.value_type
        # 1. 引擎上下文绑定参数（如 windCode / FUND_CODE）豁免显式输入
        if key == "windCode" or vtype == "FUND_CODE":
            continue

        if key in param_map:
            val = param_map[key]
            # 2. ENUM 合法性校验
            if vtype == "ENUM" and pdef.options:
                valid_options = {opt.value for opt in pdef.options}
                if val not in valid_options:
                    errors.append({
                        "code": "INVALID_METRIC_PARAMETER_ENUM",
                        "message": f"指标 {metric_name} 参数 {key} 取值 '{val}' 不在合法枚举中",
                    })
        else:
            # 3. 补齐默认值或报错必填缺失
            if pdef.default_value is not None:
                param_map[key] = pdef.default_value
                repairs.append("DEFAULT_METRIC_PARAMETER")
            elif pdef.required:
                errors.append({
                    "code": "MISSING_REQUIRED_METRIC_PARAMETER",
                    "message": f"指标 {metric_name} 缺少必填参数 {key}",
                })

    # 4. 日期顺序校验 (startDate <= endDate)
    if "startDate" in param_map and "endDate" in param_map:
        start_val = str(param_map["startDate"])
        end_val = str(param_map["endDate"])
        # 若为具体日期格式比较
        if start_val > end_val and end_val != "LATEST_TRADE_DATE":
            errors.append({
                "code": "INVALID_METRIC_DATE_RANGE",
                "message": f"startDate must be <= endDate: {start_val} > {end_val}",
            })

    filled_params = [{"key": k, "value": v} for k, v in param_map.items()]
    return errors, filled_params, repairs


def _normalize(expression: MetricExpression, contracts: dict[str, Any], repairs: list[str]) -> Clarification | None:
    if expression.metric == "f_risk_maxdownside" and isinstance(expression.value, (int, float)) and expression.value > 0:
        expression.value = -expression.value
        expression.operator = {"<": ">=", "<=": ">", ">": "<=", ">=": "<"}.get(expression.operator, expression.operator)
        repairs.append("NEGATIVE_DRAWDOWN_STORAGE")

    if expression.metric and expression.metric in contracts:
        contract = contracts[expression.metric]
        param_defs = getattr(contract, "parameters", ())
        if param_defs:
            input_p = getattr(expression, "params", []) or []
            errors, filled, p_repairs = validate_metric_parameters(expression.metric, input_p, param_defs)
            if errors:
                return Clarification(errors[0]["code"], errors[0]["message"])
            expression.params = filled
            repairs.extend(p_repairs)

    for child in expression.children or []:
        clarification = _normalize(child, contracts, repairs)
        if clarification:
            return clarification
    return None


def validate_and_repair(
    dsl: FundScreeningDsl,
    contracts: dict[str, Any] | None = None,
    group_limit_max: int = 1000,
) -> ValidationResult:
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

        clarification = _normalize(group.metric, contracts, repairs)
        if clarification:
            return ValidationResult(result, tuple(repairs), clarification)

    return ValidationResult(result, tuple(repairs))
