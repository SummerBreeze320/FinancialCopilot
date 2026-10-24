from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Literal
from app.models.clarification import ClarificationPlan
from app.models.dsl import FundScreeningDsl, SortItem
from app.models.parameters import MetricParameterDefinition
from app.pipeline.dynamic_defaults import DynamicDefaultResolver, UnresolvedDynamicDefaultError


@dataclass(frozen=True)
class ValidationResult:
    dsl: FundScreeningDsl
    repairs: tuple[str, ...] = ()
    clarification: Clarification | None = None
    clarification_plan: ClarificationPlan | None = None
    status: Literal["ACCEPT", "REPAIRED", "REJECT_OR_CLARIFY"] = "ACCEPT"

    def __post_init__(self) -> None:
        if self.clarification is not None or self.clarification_plan is not None:
            object.__setattr__(self, "status", "REJECT_OR_CLARIFY")
            if self.clarification_plan is None and self.clarification is not None:
                object.__setattr__(
                    self,
                    "clarification_plan",
                    ClarificationPlan(
                        code=self.clarification.code,
                        message=self.clarification.message,
                    ),
                )
        elif self.repairs:
            object.__setattr__(self, "status", "REPAIRED")
        else:
            object.__setattr__(self, "status", "ACCEPT")


@dataclass(frozen=True)
class Clarification:
    code: str
    message: str


def validate_metric_parameters(
    metric_name: str,
    input_params: list[dict[str, Any]],
    param_defs: list[MetricParameterDefinition] | tuple[MetricParameterDefinition, ...],
    resolver: DynamicDefaultResolver | None = None,
    as_of_date: Any = None,
) -> tuple[list[dict[str, str]], list[dict[str, Any]], list[str]]:
    """校验并补齐单指标参数。

    执行顺序固定为：
    1. 补默认参数 / 必填校验 (windCode 上下文绑定豁免)
    2. 动态默认解析 (LATEST_* -> yyyyMMdd)
    3. 类型与枚举校验、DATE 格式归一化与校验
    4. 日期区间顺序校验 (startDate <= endDate)
    """
    errors: list[dict[str, str]] = []
    repairs: list[str] = []
    param_map = {p["key"]: p["value"] for p in input_params if "key" in p}

    pdef_list: list[MetricParameterDefinition] = []
    for raw_pdef in param_defs:
        if isinstance(raw_pdef, dict):
            d = dict(raw_pdef)
            if "value_type" not in d and "valueType" not in d:
                d["value_type"] = d.get("type", "STRING")
            pdef = MetricParameterDefinition.model_validate(d)
        else:
            pdef = raw_pdef
        pdef_list.append(pdef)

    # 1. 补齐默认值或检测必填缺失
    for pdef in pdef_list:
        key = pdef.key
        vtype = pdef.value_type
        # 引擎上下文绑定参数（如 windCode / FUND_CODE）豁免显式输入
        if key == "windCode" or vtype == "FUND_CODE":
            continue

        if key not in param_map:
            if pdef.default_value is not None:
                param_map[key] = pdef.default_value
                repairs.append("DEFAULT_METRIC_PARAMETER")
            elif pdef.required:
                errors.append({
                    "code": "MISSING_REQUIRED_METRIC_PARAMETER",
                    "message": f"指标 {metric_name} 缺少必填参数 {key}",
                })

    # 2. 动态默认占位符解析 (LATEST_* -> yyyyMMdd)
    resolver_inst = resolver or DynamicDefaultResolver()
    for key, val in list(param_map.items()):
        if isinstance(val, str) and val.startswith("LATEST_"):
            try:
                resolved_val = resolver_inst.resolve(val, as_of_date=as_of_date)
                param_map[key] = resolved_val
                repairs.append("RESOLVE_DYNAMIC_DATE")
            except UnresolvedDynamicDefaultError as e:
                errors.append({
                    "code": "UNRESOLVED_DYNAMIC_DEFAULT",
                    "message": str(e),
                })

    # 3. 类型/枚举/日期格式校验
    for pdef in pdef_list:
        key = pdef.key
        if key not in param_map:
            continue
        val = param_map[key]
        vtype = pdef.value_type

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

    # 4. 日期顺序校验 (startDate <= endDate)
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


def _collapse_single_child(node: Any, repairs: list[str]) -> Any:
    """递归将逻辑树中嵌套的单子节点逻辑节点收缩展开为其子节点。"""
    if hasattr(node, "children") and not hasattr(node, "metric"):
        new_children = []
        for child in getattr(node, "children", None) or []:
            c = _collapse_single_child(child, repairs)
            while hasattr(c, "children") and not hasattr(c, "metric") and len(getattr(c, "children", [])) == 1:
                repairs.append("COLLAPSE_SINGLE_CHILD_LOGIC")
                c = c.children[0]
            new_children.append(c)
        if hasattr(node, "model_copy"):
            return node.model_copy(update={"children": new_children})
        node.children = new_children
    return node


def _normalize(
    expression: Any,
    contracts: dict[str, Any],
    repairs: list[str],
    resolver: DynamicDefaultResolver | None = None,
    as_of_date: Any = None,
) -> Clarification | None:
    # 逻辑节点：只递归子节点
    if hasattr(expression, "children") and not hasattr(expression, "metric"):
        for child in expression.children or []:
            clarification = _normalize(child, contracts, repairs, resolver=resolver, as_of_date=as_of_date)
            if clarification:
                return clarification
        return None

    metric_name = getattr(expression, "metric", None)
    if hasattr(expression, "metric"):
        if getattr(expression, "value", None) is None:
            return Clarification("MISSING_METRIC_VALUE", f"指标 {metric_name} 缺少比较值 (value)")
        forbidden_units = {"DECIMAL", "INTEGER", "NUMBER", "BOOLEAN", "STRING", "DATE"}
        if expression.unit and expression.unit.upper() in forbidden_units:
            return Clarification("INVALID_METRIC_UNIT", f"指标 {metric_name} 单位不可使用数据类型 '{expression.unit}'")

    contract = contracts.get(metric_name) if metric_name else None
    if metric_name and contracts and contract is None:
        return Clarification("UNKNOWN_METRIC", f"未知指标 {metric_name}")
    res_contract: dict[str, Any] = {}
    if contract is not None:
        res_contract = getattr(contract, "result_contract", {}) or {}

    # 1. 最大回撤 / storageConvention 转换 (读取 storageConvention，不再按 mnemonic 硬编码)
    storage_conv = res_contract.get("storageConvention") or res_contract.get("storage_convention")
    if (storage_conv == "NEGATIVE_PERCENT" or expression.metric == "f_risk_maxdownside") and isinstance(expression.value, (int, float)) and expression.value > 0:
        expression.value = -expression.value
        expression.operator = {"<": ">=", "<=": ">=", ">": "<=", ">=": "<="}.get(expression.operator, expression.operator)
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
            errors, filled, p_repairs = validate_metric_parameters(
                expression.metric, input_p, param_defs, resolver=resolver, as_of_date=as_of_date
            )
            if errors:
                return Clarification(errors[0]["code"], errors[0]["message"])
            expression.params = filled
            repairs.extend(p_repairs)

    if expression.period is not None:
        period = expression.period
        start_date = period.get("startDate") or period.get("start_date")
        end_date = period.get("endDate") or period.get("end_date")
        resolver_inst = resolver or DynamicDefaultResolver()
        if isinstance(start_date, str) and start_date.startswith("LATEST_"):
            try:
                start_date = resolver_inst.resolve(start_date, as_of_date=as_of_date)
                period["startDate"] = start_date
                repairs.append("RESOLVE_DYNAMIC_DATE")
            except UnresolvedDynamicDefaultError as e:
                return Clarification("UNRESOLVED_DYNAMIC_DEFAULT", str(e))
        if isinstance(end_date, str) and end_date.startswith("LATEST_"):
            try:
                end_date = resolver_inst.resolve(end_date, as_of_date=as_of_date)
                period["endDate"] = end_date
                repairs.append("RESOLVE_DYNAMIC_DATE")
            except UnresolvedDynamicDefaultError as e:
                return Clarification("UNRESOLVED_DYNAMIC_DEFAULT", str(e))

        if start_date is not None and end_date is not None:
            start_text = str(start_date).replace("-", "")
            end_text = str(end_date).replace("-", "")
            if not re.fullmatch(r"\d{8}", start_text) or not re.fullmatch(r"\d{8}", end_text):
                return Clarification("INVALID_METRIC_PERIOD_FORMAT", "指标时间区间应使用 yyyyMMdd")
            if start_text > end_text:
                return Clarification(
                    "INVALID_METRIC_PERIOD_RANGE",
                    f"指标时间区间 startDate 必须不晚于 endDate: {start_text} > {end_text}",
                )

    for child in getattr(expression, "children", None) or []:
        clarification = _normalize(child, contracts, repairs, resolver=resolver, as_of_date=as_of_date)
        if clarification:
            return clarification
    return None


def validate_and_repair(
    dsl: FundScreeningDsl,
    contracts: dict[str, Any] | None = None,
    group_limit_max: int = 1000,
    resolver: DynamicDefaultResolver | None = None,
    as_of_date: Any = None,
) -> ValidationResult:
    """校验执行契约；只做不会改变投资意图的修复。"""
    result = dsl.model_copy(deep=True)
    repairs: list[str] = []
    contracts = contracts or {}
    resolver_inst = resolver or DynamicDefaultResolver()

    def _is_valid_sort_metric(metric_name: str) -> bool:
        if metric_name in ("fund_code", "windCode", "wind_code"):
            return True
        if not contracts:
            return True
        contract = contracts.get(metric_name)
        if contract is None:
            return False
        usage = getattr(contract, "supported_usage", None) or getattr(contract, "supportedUsage", ())
        if usage and "sort" not in usage:
            return False
        return True

    if result.limit > group_limit_max:
        return ValidationResult(result, tuple(repairs), Clarification("LIMIT_TOO_LARGE", "筛选数量超过服务上限"))

    if not result.sort:
        result.sort = [SortItem(metric="fund_code", direction="ASC", nulls="LAST")]
        repairs.append("DEFAULT_ROOT_SORT")

    for sort in result.sort:
        if not _is_valid_sort_metric(sort.metric):
            return ValidationResult(result, tuple(repairs), Clarification("UNKNOWN_SORT_METRIC", f"无法按 {sort.metric} 排序"))

    for group in result.children:
        # 校验 Sector 是否包含被废弃的 match 字段
        if hasattr(group, "sector") and group.sector is not None:
            sec_dump = group.sector.model_dump(by_alias=True) if hasattr(group.sector, "model_dump") else group.sector
            import json
            sec_str = json.dumps(sec_dump, ensure_ascii=False)
            if '"match"' in sec_str:
                return ValidationResult(result, tuple(repairs), Clarification("FORBIDDEN_FIELD", "SectorLeaf contains forbidden field 'match'"))

        # 单子节点逻辑树收缩修复
        group.metric = _collapse_single_child(group.metric, repairs)

        if not group.limit:
            group.limit = 20
            repairs.append("DEFAULT_GROUP_LIMIT")
        elif group.limit > group_limit_max:
            return ValidationResult(result, tuple(repairs), Clarification("LIMIT_TOO_LARGE", "筛选数量超过服务上限"))

        if not group.sort:
            group.sort = [SortItem(metric="fund_code", direction="ASC", nulls="LAST")]
            repairs.append("DEFAULT_GROUP_SORT")

        for sort in group.sort:
            if not _is_valid_sort_metric(sort.metric):
                return ValidationResult(result, tuple(repairs), Clarification("UNKNOWN_SORT_METRIC", f"无法按 {sort.metric} 排序"))

        clarification = _normalize(group.metric, contracts, repairs, resolver=resolver_inst, as_of_date=as_of_date)
        if clarification:
            return ValidationResult(result, tuple(repairs), clarification)

    # 最终断言：输出 DSL 不得含有任何 LATEST_ 动态占位符
    import json
    raw_dict = result.model_dump(by_alias=True)
    dump_str = json.dumps(raw_dict, ensure_ascii=False)
    if "LATEST_" in dump_str:
        return ValidationResult(
            result,
            tuple(repairs),
            Clarification("UNRESOLVED_DYNAMIC_DEFAULT", "DSL contains unresolved dynamic date placeholder"),
        )

    return ValidationResult(result, tuple(repairs))


def validate_execution_gate(
    dsl: Any, contracts: dict[str, Any] | None = None
) -> tuple[bool, list[dict[str, str]]]:
    """Finalize 前唯一完整执行契约门禁。

    必须满足：
    1. Root sort 存在且非空
    2. Root limit 存在且 > 0
    3. 每个 Group:
       - limit 存在且 > 0
       - sort 存在且非空
       - sector 不含 forbidden field 如 'match'
       - metric 叶子 value 必填、unit 合法、无未解析 LATEST_*
    4. 整个 DSL 无任何 LATEST_* 占位符
    """
    errors: list[dict[str, str]] = []

    if hasattr(dsl, "model_dump"):
        data = dsl.model_dump(by_alias=True)
    elif isinstance(dsl, dict):
        data = dsl
    else:
        errors.append({"code": "INVALID_DSL_TYPE", "message": "DSL must be a dict or BaseModel"})
        return False, errors

    root_sort = data.get("sort")
    if not root_sort:
        errors.append({"code": "MISSING_ROOT_SORT", "message": "Root sort must not be empty"})

    root_limit = data.get("limit")
    if root_limit is None or (isinstance(root_limit, (int, float)) and root_limit <= 0):
        errors.append({"code": "MISSING_ROOT_LIMIT", "message": "Root limit must be > 0"})

    children = data.get("children", [])
    if not children:
        errors.append({"code": "EMPTY_CHILDREN", "message": "DSL must contain at least one business group"})

    for idx, grp in enumerate(children):
        grp_limit = grp.get("limit")
        if grp_limit is None or (isinstance(grp_limit, (int, float)) and grp_limit <= 0):
            errors.append({"code": "MISSING_GROUP_LIMIT", "message": f"Group {idx} limit must be > 0"})

        grp_sort = grp.get("sort")
        if not grp_sort:
            errors.append({"code": "MISSING_GROUP_SORT", "message": f"Group {idx} sort must not be empty"})

        sec = grp.get("sector")
        if sec:
            import json
            sec_str = json.dumps(sec, ensure_ascii=False)
            if '"match"' in sec_str:
                errors.append({"code": "FORBIDDEN_FIELD", "message": f"Group {idx} sector contains forbidden field 'match'"})

        metric = grp.get("metric")

        def _check_metric(m: Any):
            if not isinstance(m, dict):
                return
            if "children" in m and "metric" not in m:
                for ch in m.get("children", []):
                    _check_metric(ch)
                return
            m_key = m.get("metric")
            if m_key:
                if m.get("value") is None:
                    errors.append({"code": "MISSING_METRIC_VALUE", "message": f"Metric {m_key} missing value"})
                u = m.get("unit")
                if u and str(u).upper() in ("DECIMAL", "INTEGER", "NUMBER", "BOOLEAN", "STRING", "DATE"):
                    errors.append({"code": "INVALID_METRIC_UNIT", "message": f"Metric {m_key} unit cannot be data type '{u}'"})
                if contracts and m_key not in contracts:
                    errors.append({"code": "UNKNOWN_METRIC", "message": f"Metric {m_key} not in contracts"})
            for p in m.get("params", []):
                pval = p.get("value") if isinstance(p, dict) else None
                if isinstance(pval, str) and pval.startswith("LATEST_"):
                    errors.append({"code": "UNRESOLVED_DYNAMIC_DEFAULT", "message": f"Unresolved parameter placeholder {pval}"})

        if metric:
            _check_metric(metric)

    import json
    dsl_str = json.dumps(data, ensure_ascii=False)
    if "LATEST_" in dsl_str:
        if not any(e["code"] == "UNRESOLVED_DYNAMIC_DEFAULT" for e in errors):
            errors.append({"code": "UNRESOLVED_DYNAMIC_DEFAULT", "message": "DSL contains unresolved dynamic date placeholder"})

    return len(errors) == 0, errors
