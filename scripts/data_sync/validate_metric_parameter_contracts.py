#!/usr/bin/env python3
"""Validator for fund metric parameter contracts.

Pure standard library implementation - no external jsonschema dependency.
"""

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Union

ALLOWED_VALUE_TYPES = {
    "STRING",
    "DATE",
    "INTEGER",
    "DECIMAL",
    "BOOLEAN",
    "ENUM",
}

ALLOWED_RESULT_VALUE_TYPES = {
    "STRING",
    "DATE",
    "INTEGER",
    "DECIMAL",
    "BOOLEAN",
    "ENUM",
}

ALLOWED_CONTRACT_STATUSES = {
    "INFERRED",
    "REVIEWED",
    "NEEDS_REVIEW",
}

ALLOWED_OPERATORS = {">", ">=", "<", "<=", "=", "!="}
MAGNITUDE_OPERATORS = {">", ">=", "<", "<="}

ALLOWED_UNITS = {"%", "元", "万元", "亿元", "天", "月", "年", "名", "次", "只", "户"}
PROHIBITED_ENGLISH_UNITS = {"PERCENT", "NONE", "YUAN", "DAYS", "YEARS", "MONTHS", "COUNT"}

KEY_REGEX = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]*$")


@dataclass
class ValidationReport:
    total: int
    valid: int
    errors: list[str] = field(default_factory=list)

    @property
    def is_success(self) -> bool:
        return len(self.errors) == 0 and self.valid == self.total


def validate_metric_contract(metric: dict[str, Any]) -> tuple[bool, list[str]]:
    """Validate a single metric dictionary for parameter and result contract compliance.

    Returns:
        (is_valid, errors) where errors are human-readable strings formatted as:
        `[<mnemonic>] <json_path>: <message>`
    """
    errors: list[str] = []
    mnemonic = str(metric.get("mnemonic", "<unknown>"))

    # 1. Top-level required contract fields
    for req_field in ["parameters", "result", "parameter_schema_version", "contract_status"]:
        if req_field not in metric:
            errors.append(f"[{mnemonic}] {req_field}: required contract field is missing")

    # 2. contract_status check
    status = metric.get("contract_status")
    if status is not None:
        if not isinstance(status, str) or status not in ALLOWED_CONTRACT_STATUSES:
            errors.append(
                f"[{mnemonic}] contract_status: invalid contract_status '{status}', "
                f"must be one of {sorted(ALLOWED_CONTRACT_STATUSES)}"
            )

    # 3. parameter_schema_version check
    schema_ver = metric.get("parameter_schema_version")
    if schema_ver is not None:
        if isinstance(schema_ver, bool) or not isinstance(schema_ver, int) or schema_ver < 1:
            errors.append(
                f"[{mnemonic}] parameter_schema_version: must be an integer >= 1, got {schema_ver!r}"
            )

    # 4. result contract check
    result = metric.get("result")
    if result is not None:
        if not isinstance(result, dict):
            errors.append(f"[{mnemonic}] result: must be an object, got {type(result).__name__}")
        else:
            # Required result fields
            for r_req in ["valueType", "supportedOperators"]:
                if r_req not in result:
                    errors.append(f"[{mnemonic}] result.{r_req}: required result field is missing")

            res_vtype = result.get("valueType")
            if res_vtype is not None:
                if not isinstance(res_vtype, str) or res_vtype not in ALLOWED_RESULT_VALUE_TYPES:
                    errors.append(
                        f"[{mnemonic}] result.valueType: invalid valueType '{res_vtype}', "
                        f"must be one of {sorted(ALLOWED_RESULT_VALUE_TYPES)}"
                    )

            # precision rules
            precision = result.get("precision")
            if res_vtype == "DECIMAL":
                if precision is None:
                    errors.append(f"[{mnemonic}] result.precision: precision is required for DECIMAL results")
                elif not isinstance(precision, dict):
                    errors.append(f"[{mnemonic}] result.precision: must be an object")
                else:
                    for p_field in ["totalDigits", "scale", "roundingMode"]:
                        if p_field not in precision:
                            errors.append(f"[{mnemonic}] result.precision.{p_field}: required field missing")
                    td = precision.get("totalDigits")
                    if td is not None and (isinstance(td, bool) or not isinstance(td, int) or td < 1):
                        errors.append(f"[{mnemonic}] result.precision.totalDigits: must be an integer >= 1")
                    sc = precision.get("scale")
                    if sc is not None and (isinstance(sc, bool) or not isinstance(sc, int) or sc < 0):
                        errors.append(f"[{mnemonic}] result.precision.scale: must be an integer >= 0")
            elif precision is not None:
                errors.append(
                    f"[{mnemonic}] result.precision: precision is only allowed for DECIMAL results, got '{res_vtype}'"
                )

            # displayScale
            display_scale = result.get("displayScale")
            if display_scale is not None:
                if isinstance(display_scale, bool) or not isinstance(display_scale, int) or display_scale < 0:
                    errors.append(f"[{mnemonic}] result.displayScale: must be an integer >= 0")

            # unit validation
            if "unit" in result:
                unit = result.get("unit")
                if unit is not None:
                    if not isinstance(unit, str):
                        errors.append(f"[{mnemonic}] result.unit: unit must be a string or null")
                    elif unit in PROHIBITED_ENGLISH_UNITS:
                        errors.append(
                            f"[{mnemonic}] result.unit: English unit code '{unit}' is prohibited; "
                            "use real symbols like %, 元, or null"
                        )
                    elif unit not in ALLOWED_UNITS:
                        errors.append(
                            f"[{mnemonic}] result.unit: invalid unit '{unit}', "
                            f"must be one of {sorted(ALLOWED_UNITS)} or null"
                        )

            # supportedOperators validation
            ops = result.get("supportedOperators")
            if ops is not None:
                if not isinstance(ops, list) or len(ops) == 0:
                    errors.append(f"[{mnemonic}] result.supportedOperators: must be a non-empty list")
                else:
                    invalid_ops = [op for op in ops if op not in ALLOWED_OPERATORS]
                    if invalid_ops:
                        errors.append(
                            f"[{mnemonic}] result.supportedOperators: unsupported operator(s) {invalid_ops}, "
                            f"must be from {sorted(ALLOWED_OPERATORS)}"
                        )
                    if res_vtype in ("STRING", "BOOLEAN", "ENUM"):
                        mag_ops = [op for op in ops if op in MAGNITUDE_OPERATORS]
                        if mag_ops:
                            errors.append(
                                f"[{mnemonic}] result.supportedOperators: magnitude operators {mag_ops} "
                                f"are not allowed for {res_vtype} results (only '=' and '!=')"
                            )

            # result ENUM options validation
            if res_vtype == "ENUM":
                r_options = result.get("options")
                if not isinstance(r_options, list) or len(r_options) == 0:
                    errors.append(f"[{mnemonic}] result.options: ENUM result requires non-empty options list")
                else:
                    seen_vals: set[int] = set()
                    for opt_idx, opt in enumerate(r_options):
                        opt_prefix = f"[{mnemonic}] result.options[{opt_idx}]"
                        if not isinstance(opt, dict):
                            errors.append(f"{opt_prefix}: option must be an object")
                            continue
                        if "value" not in opt or "label" not in opt:
                            errors.append(f"{opt_prefix}: option requires 'value' and 'label'")
                            continue
                        val = opt.get("value")
                        if isinstance(val, bool) or not isinstance(val, int):
                            errors.append(f"{opt_prefix}.value: option value must be an integer, got {val!r}")
                        elif val in seen_vals:
                            errors.append(f"{opt_prefix}.value: duplicate enum option value {val}")
                        else:
                            seen_vals.add(val)
                        lbl = opt.get("label")
                        if not isinstance(lbl, str) or not lbl.strip():
                            errors.append(f"{opt_prefix}.label: option label must be non-empty string")

    # 5. parameters check
    params = metric.get("parameters")
    if params is None and "parameters" in metric:
        errors.append(f"[{mnemonic}] parameters: cannot be null, must be a list")
    elif params is not None:
        if not isinstance(params, list):
            errors.append(
                f"[{mnemonic}] parameters: must be a list, got {type(params).__name__}"
            )
        else:
            param_by_key: dict[str, dict[str, Any]] = {}
            for idx, param in enumerate(params):
                prefix = f"[{mnemonic}] parameters[{idx}]"
                if not isinstance(param, dict):
                    errors.append(f"{prefix}: parameter item must be an object, got {type(param).__name__}")
                    continue

                # Position check: must start at 1, increment sequentially, matching array index exactly
                expected_pos = idx + 1
                pos = param.get("position")
                if pos is None:
                    errors.append(f"{prefix}.position: position field is required")
                elif isinstance(pos, bool) or not isinstance(pos, int):
                    errors.append(f"{prefix}.position: position must be an integer, got {pos!r}")
                elif pos != expected_pos:
                    errors.append(
                        f"{prefix}.position: expected position {expected_pos}, got {pos} "
                        "(array order and position must match without gaps or duplicates)"
                    )

                # Required parameter fields
                for p_req in ["key", "valueType", "required"]:
                    if p_req not in param:
                        errors.append(f"{prefix}.{p_req}: parameter field is required")

                key = param.get("key")
                if key is not None:
                    if not isinstance(key, str) or not KEY_REGEX.match(key):
                        errors.append(
                            f"{prefix}.key: invalid parameter key '{key}', must match ^[a-zA-Z][a-zA-Z0-9_]*$"
                        )
                    elif key in param_by_key:
                        errors.append(f"{prefix}.key: duplicate parameter key '{key}' in metric")
                    else:
                        param_by_key[key] = param

                v_type = param.get("valueType")
                if v_type is not None:
                    if not isinstance(v_type, str) or v_type not in ALLOWED_VALUE_TYPES:
                        errors.append(
                            f"{prefix}.valueType: invalid valueType '{v_type}', "
                            f"must be one of {sorted(ALLOWED_VALUE_TYPES)}"
                        )

                req = param.get("required")
                if req is not None and not isinstance(req, bool):
                    errors.append(f"{prefix}.required: required must be a boolean, got {req!r}")

                # Date specific rules
                if v_type == "DATE":
                    fmt = param.get("format")
                    if fmt != "yyyyMMdd":
                        errors.append(
                            f"{prefix}.format: DATE parameter requires format 'yyyyMMdd', got {fmt!r}"
                        )

                # Enum specific rules
                if v_type == "ENUM":
                    options = param.get("options")
                    if not isinstance(options, list) or len(options) == 0:
                        errors.append(
                            f"{prefix}.options: ENUM parameter requires non-empty options list"
                        )
                    else:
                        seen_values: set[int] = set()
                        for opt_idx, opt in enumerate(options):
                            opt_prefix = f"{prefix}.options[{opt_idx}]"
                            if not isinstance(opt, dict):
                                errors.append(f"{opt_prefix}: option must be an object")
                                continue
                            if "value" not in opt or "label" not in opt:
                                errors.append(f"{opt_prefix}: option requires 'value' and 'label'")
                                continue

                            val = opt.get("value")
                            if isinstance(val, bool) or not isinstance(val, int):
                                errors.append(
                                    f"{opt_prefix}.value: option value must be an integer, got {val!r}"
                                )
                            elif val in seen_values:
                                errors.append(
                                    f"{opt_prefix}.value: duplicate enum option value {val}"
                                )
                            else:
                                seen_values.add(val)

                            lbl = opt.get("label")
                            if not isinstance(lbl, str) or not lbl.strip():
                                errors.append(f"{opt_prefix}.label: option label must be non-empty string")

                            aliases = opt.get("aliases")
                            if aliases is not None:
                                if not isinstance(aliases, list) or not all(isinstance(a, str) for a in aliases):
                                    errors.append(f"{opt_prefix}.aliases: aliases must be list of strings")

            # Check pairWith mutual relationship across parameters
            for idx, param in enumerate(params):
                if isinstance(param, dict) and "pairWith" in param:
                    target_key = param.get("pairWith")
                    curr_key = param.get("key")
                    prefix = f"[{mnemonic}] parameters[{idx}].pairWith"
                    if not target_key or target_key not in param_by_key:
                        errors.append(
                            f"{prefix}: references non-existent parameter '{target_key}'"
                        )
                    else:
                        target_param = param_by_key[target_key]
                        if target_param.get("pairWith") != curr_key:
                            errors.append(
                                f"{prefix}: unilateral pairWith '{target_key}', "
                                f"target does not pair back to '{curr_key}'"
                            )

    return len(errors) == 0, errors


def validate_catalog(path: Union[str, Path]) -> ValidationReport:
    """Validate all metrics in a metrics JSON catalog file."""
    catalog_path = Path(path)
    if not catalog_path.exists():
        raise FileNotFoundError(f"Catalog file not found: {catalog_path}")

    with catalog_path.open("r", encoding="utf-8") as f:
        data = json.load(f)

    if not isinstance(data, list):
        raise ValueError(f"Catalog content must be a JSON array, got {type(data).__name__}")

    total = len(data)
    valid_count = 0
    all_errors: list[str] = []

    for metric in data:
        if not isinstance(metric, dict):
            all_errors.append(f"Catalog item is not an object: {metric!r}")
            continue
        is_valid, errors = validate_metric_contract(metric)
        if is_valid:
            valid_count += 1
        else:
            all_errors.extend(errors)

    return ValidationReport(total=total, valid=valid_count, errors=all_errors)


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else "docs/temp/metrics.json"
    target = Path(path)
    print(f"Validating metric parameter contracts in: {target}")

    try:
        report = validate_catalog(target)
    except Exception as e:
        print(f"Error loading catalog: {e}", file=sys.stderr)
        return 1

    print(f"Validation summary: {report.valid}/{report.total} metrics valid.")
    if report.errors:
        print(f"Encountered {len(report.errors)} error(s):")
        for err in report.errors[:50]:
            print(f"  - {err}")
        if len(report.errors) > 50:
            print(f"  ... and {len(report.errors) - 50} more errors.")
        return 1

    print("All metric parameter contracts are valid!")
    return 0


if __name__ == "__main__":
    sys.exit(main())
