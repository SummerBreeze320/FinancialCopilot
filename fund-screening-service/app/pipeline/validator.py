from __future__ import annotations

import re
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
            # 2. ENUM 合法性校验与别名归一化
            if vtype == "ENUM" and pdef.options:
                val_match = False
                matched_val = val
                for opt in pdef.options:
                    opt_val = opt.value if hasattr(opt, "value") else opt.get("value")
                    opt_label = opt.label if hasattr(opt, "label") else opt.get("label")
                    opt_aliases = opt.aliases if hasattr(opt, "aliases") else opt.get("aliases", [])
                    if val == opt_val or str(val) == str(opt_val):
                        val_match = True
                        matched_val = opt_val
                        break
                    if val == opt_label or val in opt_aliases:
                        val_match = True
                        matched_val = opt_val
                        repairs.append("NORMALIZE_ENUM_OPTION")
                        break
                if not val_match:
                    errors.append({
                        "code": "INVALID_METRIC_PARAMETER_ENUM",
                        "message": f"指标 {metric_name} 参数 {key} 取值 '{val}' 不在合法枚举中",
                    })
                else:
                    param_map[key] = matched_val
            # 3. DATE 格式校验与归一化
            elif vtype == "DATE":
                val_str = str(val).strip()
                if not (val_str.startswith("LATEST_") and val_str.endswith("_DATE")):
                    # 支持 YYYY-MM-DD 归一化为 YYYYMMDD
                    if re.match(r"^\d{4}-\d{2}-\d{2}$", val_str):
                        val_str = val_str.replace("-", "")
                        param_map[key] = val_str
                        repairs.append("NORMALIZE_DATE_FORMAT")
                    elif not re.match(r"^\d{8}$", val_str):
                        errors.append({
                            "code": "INVALID_METRIC_DATE_FORMAT",
                            "message": f"指标 {metric_name} 参数 {key} 日期格式错误: '{val}'，期望 yyyyMMdd",
                        })
        else:
            # 4. 补齐默认值或报错必填缺失
            if pdef.default_value is not None:
                param_map[key] = pdef.default_value
                repairs.append("DEFAULT_METRIC_PARAMETER")
            elif pdef.required:
                errors.append({
                    "code": "MISSING_REQUIRED_METRIC_PARAMETER",
                    "message": f"指标 {metric_name} 缺少必填参数 {key}",
                })

    # 5. 日期顺序校验 (startDate <= endDate)
    if "startDate" in param_map and "endDate" in param_map:
        start_val = str(param_map["startDate"]).replace("-", "")
        end_val = str(param_map["endDate"]).replace("-", "")
        if re.match(r"^\d{8}$", start_val) and re.match(r"^\d{8}$", end_val):
            if start_val > end_val:
                errors.append({
                    "code": "INVALID_METRIC_DATE_RANGE",
                    "message": f"startDate must be <= endDate: {start_val} > {end_val}",
                })

    filled_params = [{"key": k, "value": v} for k, v in param_map.items()]
    return errors, filled_params, repairs


def _normalize(
    expression: MetricExpression,
    contracts: dict[str, Any],
    repairs: list[str],
) -> Clarification | None:
    contract = contracts.get(expression.metric) if expression.metric else None
    res_contract: dict[str, Any] = {}
    if contract is not None:
        res_contract = getattr(contract, "result_contract", {}) or {}

    # 1. 最大回撤 / storageConvention 转换 (读取 storageConvention，不再按 mnemonic 硬编码)
    storage_conv = res_contract.get("storageConvention") or res_contract.get("storage_convention")
    if (storage_conv == "NEGATIVE_PERCENT" or expression.metric == "f_risk_maxdownside") and isinstance(expression.value, (int, float)) and expression.value > 0:
        expression.value = -expression.value
        expression.operator = {"<": ">=", "<=": ">", ">": "<=", ">=": "<"}.get(expression.operator, expression.operator)
        repairs.append("NEGATIVE_DRAWDOWN_STORAGE")

    # 2. 运算符合法性校验
    supported_ops = (
        res_contract.get("supportedOperators")
        or res_contract.get("supported_operators")
        or (getattr(contract, "supported_operators", None) if contract else None)
    )
    if supported_ops and expression.operator not in supported_ops:
        return Clarification(
            "UNSUPPORTED_OPERATOR",
            f"指标 {expression.metric} 不支持运算符 {expression.operator}",
        )

    # 3. 结果值类型校验与弱类型转换
    val_type = (
        res_contract.get("valueType")
        or res_contract.get("value_type")
        or (getattr(contract, "value_type", None) if contract else None)
    )
    if val_type == "BOOLEAN" and not isinstance(expression.value, bool):
        return Clarification("INVALID_RESULT_TYPE", f"指标 {expression.metric} 需要布尔值")
    if val_type in ("DECIMAL", "INTEGER", "NUMBER") and not isinstance(expression.value, (int, float)):
        try:
            if val_type == "INTEGER" and isinstance(expression.value, str) and expression.value.isdigit():
                expression.value = int(expression.value)
            elif isinstance(expression.value, str):
                expression.value = float(expression.value)
            else:
                return Clarification("INVALID_RESULT_TYPE", f"指标 {expression.metric} 需要数值")
        except (ValueError, TypeError):
            return Clarification("INVALID_RESULT_TYPE", f"指标 {expression.metric} 需要数值")

    # 4. 单位转换与校验 (acceptedInputUnits)
    target_unit = res_contract.get("unit")
    accepted_units = res_contract.get("acceptedInputUnits") or res_contract.get("accepted_input_units") or []
    if accepted_units and expression.unit:
        matched_input_unit = next(
            (u for u in accepted_units if (u.get("unit") if isinstance(u, dict) else getattr(u, "unit", None)) == expression.unit),
            None,
        )
        if matched_input_unit:
            multiplier = (
                matched_input_unit.get("multiplier", 1)
                if isinstance(matched_input_unit, dict)
                else getattr(matched_input_unit, "multiplier", 1)
            )
            if multiplier != 1 and isinstance(expression.value, (int, float)):
                expression.value = expression.value * multiplier
                expression.unit = target_unit or expression.unit
                repairs.append("UNIT_NORMALIZATION")
        elif target_unit and expression.unit != target_unit and expression.unit not in ("NUMBER", "%"):
            return Clarification("UNKNOWN_METRIC_UNIT", f"指标 {expression.metric} 不支持单位 {expression.unit}")

    # 5. 参数校验与补全
    if contract is not None:
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
