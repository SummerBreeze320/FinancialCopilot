from __future__ import annotations

import re
from datetime import datetime
from typing import Any

from app.models.parameters import MetricContract, MetricParameterDefinition


def _get_parameter_defs(contract: Any) -> list[MetricParameterDefinition]:
    if isinstance(contract, MetricContract):
        return sorted(contract.parameters, key=lambda p: p.position)

    raw_params = getattr(contract, "parameters", None)
    if not raw_params:
        return []

    pdefs: list[MetricParameterDefinition] = []
    for item in raw_params:
        if isinstance(item, MetricParameterDefinition):
            pdefs.append(item)
        elif isinstance(item, dict):
            pdefs.append(MetricParameterDefinition.model_validate(item))
        else:
            pdefs.append(item)
    return sorted(pdefs, key=lambda p: getattr(p, "position", 0))


def serialize_metric_call(
    metric: str,
    contract: Any,
    values: dict[str, Any],
) -> str:
    """按 position 严格递增顺序序列化指标函数调用。

    执行确定性校验：
    1. 拒绝未知 key；
    2. 检查必填参数与默认值回退；
    3. 校验 ENUM 整数/取值范围；
    4. 校验 DATE 的 yyyyMMdd 格式；
    5. 校验 startDate <= endDate。
    """
    param_defs = _get_parameter_defs(contract)
    valid_keys = {p.key for p in param_defs}

    # 1. 未知 key 检查
    for key in values:
        if key not in valid_keys:
            raise ValueError(f"Unknown parameter '{key}' for metric '{metric}'")

    resolved_values: dict[str, Any] = {}

    # 2. 按 position 逐一解析并验证
    for p in param_defs:
        key = p.key
        if key in values:
            val = values[key]
        elif p.default_value is not None:
            val = p.default_value
        elif p.required:
            raise ValueError(f"Missing required parameter '{key}' for metric '{metric}'")
        else:
            val = None

        # 3. ENUM 校验
        if p.value_type == "ENUM" and p.options and val is not None:
            valid_option_values = {opt.value for opt in p.options}
            # 同时支持数字和数字字符串等价比较
            val_match = False
            for opt_val in valid_option_values:
                if val == opt_val or str(val) == str(opt_val):
                    val_match = True
                    break
            if not val_match:
                raise ValueError(
                    f"Invalid enum value '{val}' for parameter '{key}' in metric '{metric}'. "
                    f"Valid options: {list(valid_option_values)}"
                )

        # 4. DATE 校验
        if p.value_type == "DATE" and val is not None:
            val_str = str(val)
            # 允许系统占位符如 LATEST_TRADE_DATE, LATEST_REPORT_DATE
            if not (val_str.startswith("LATEST_") and val_str.endswith("_DATE")):
                if p.format == "yyyyMMdd" or not p.format:
                    if not re.match(r"^\d{8}$", val_str):
                        raise ValueError(
                            f"Invalid date format for '{key}': '{val}', expected yyyyMMdd"
                        )
                    try:
                        datetime.strptime(val_str, "%Y%m%d")
                    except ValueError as e:
                        raise ValueError(
                            f"Invalid date format for '{key}': '{val}', expected yyyyMMdd ({e})"
                        )

        resolved_values[key] = val

    # 5. 日期区间逆序校验
    if "startDate" in resolved_values and "endDate" in resolved_values:
        start_val = str(resolved_values["startDate"])
        end_val = str(resolved_values["endDate"])
        if re.match(r"^\d{8}$", start_val) and re.match(r"^\d{8}$", end_val):
            if start_val > end_val:
                raise ValueError(
                    f"startDate must be <= endDate: {start_val} > {end_val}"
                )

    # 6. 按 position 严格排序输出参数实参
    args: list[str] = []
    for p in param_defs:
        val = resolved_values.get(p.key)
        args.append(str(val) if val is not None else "")

    return f"{metric}({','.join(args)})"
