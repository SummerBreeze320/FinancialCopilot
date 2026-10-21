"""Generate a read-only coverage report for metric parameter and result contracts."""

import json
import sys
from collections import Counter
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from scripts.data_sync.validate_metric_parameter_contracts import validate_catalog


RETURN_MARKET_PREFIXES = (
    "f_return_",
    "f_nav_",
    "f_mmf_",
    "f_dq_",
    "f_pq_",
    "s_pq_",
    "f_quot_",
    "f_div_",
    "f_netasset_",
    "f_pchredm_",
    "f_customizedfundornot",
    "f_issue_",
)
PORTFOLIO_HOLDER_STYLE_PREFIXES = ("f_prt_", "f_holder_", "f_style_")


def categorize_family(mnemonic: str) -> str:
    if mnemonic.startswith("f_info_"):
        return "info"
    if any(mnemonic.startswith(prefix) for prefix in RETURN_MARKET_PREFIXES):
        return "return_market_event"
    if mnemonic.startswith("f_risk_"):
        return "risk"
    if any(mnemonic.startswith(prefix) for prefix in PORTFOLIO_HOLDER_STYLE_PREFIXES):
        return "portfolio_holder_style"
    return "other"


def generate_coverage_report(
    catalog_path: Path, output_path: Path | None = None
) -> dict:
    validation = validate_catalog(catalog_path)
    with catalog_path.open("r", encoding="utf-8") as f:
        metrics = json.load(f)

    family_counts = Counter()
    status_counts = Counter()
    param_type_counts = Counter()
    result_type_counts = Counter()
    unit_counts = Counter()
    operator_combo_counts = Counter()

    decimal_count = 0
    precision_count = 0
    display_scale_count = 0
    non_decimal_precision_count = 0

    parameter_enums = []
    result_enums = []
    defaults = []
    storage_conventions = []

    for m in metrics:
        mnemonic = m.get("mnemonic", "")
        family = categorize_family(mnemonic)
        family_counts[family] += 1

        status = m.get("contract_status", "UNKNOWN")
        status_counts[status] += 1

        for p in m.get("parameters", []):
            p_type = p.get("valueType", "UNKNOWN")
            param_type_counts[p_type] += 1

            if p.get("options"):
                parameter_enums.append(
                    {
                        "metric": mnemonic,
                        "parameter": p.get("key"),
                        "position": p.get("position"),
                        "options": p.get("options"),
                    }
                )

            if "defaultValue" in p or "defaultDynamic" in p:
                defaults.append(
                    {
                        "metric": mnemonic,
                        "parameter": p.get("key"),
                        "position": p.get("position"),
                        "defaultValue": p.get("defaultValue"),
                        "defaultDynamic": p.get("defaultDynamic"),
                    }
                )

        result = m.get("result", {})
        res_type = result.get("valueType", "UNKNOWN")
        result_type_counts[res_type] += 1

        if res_type == "DECIMAL":
            decimal_count += 1
            if result.get("precision"):
                precision_count += 1
            if "displayScale" in result:
                display_scale_count += 1
        elif result.get("precision"):
            non_decimal_precision_count += 1

        unit = result.get("unit")
        unit_key = "null" if unit is None else unit
        unit_counts[unit_key] += 1

        operators = tuple(result.get("supportedOperators", []))
        operator_combo_counts[" ".join(operators)] += 1

        if result.get("storageConvention"):
            storage_conventions.append(
                {
                    "metric": mnemonic,
                    "storageConvention": result.get("storageConvention"),
                }
            )

        if result.get("options"):
            result_enums.append(
                {
                    "metric": mnemonic,
                    "options": result.get("options"),
                }
            )

    report = {
        "summary": {
            "total_metrics": len(metrics),
            "catalog_path": str(catalog_path).replace("\\", "/"),
            "schema_version": 1,
            "validation": {
                "valid": validation.valid,
                "total": validation.total,
                "errors_count": len(validation.errors),
                "errors": validation.errors,
            },
        },
        "by_family": dict(family_counts),
        "by_contract_status": dict(status_counts),
        "by_parameter_value_type": dict(param_type_counts),
        "by_result_value_type": dict(result_type_counts),
        "by_symbol_unit": dict(unit_counts),
        "by_supported_operators": dict(operator_combo_counts),
        "precision_coverage": {
            "decimal_metrics_total": decimal_count,
            "metrics_with_precision": precision_count,
            "metrics_with_display_scale": display_scale_count,
            "non_decimal_with_precision": non_decimal_precision_count,
        },
        "storage_conventions": storage_conventions,
        "result_enums": result_enums,
        "parameter_enums": parameter_enums,
        "defaults": defaults,
    }

    if output_path is not None:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with output_path.open("w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)

    return report


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(
        description="Generate metric parameter and result contract coverage report."
    )
    parser.add_argument(
        "--catalog",
        type=Path,
        default=Path("docs/temp/metrics.json"),
        help="Path to metrics.json",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("docs/temp/metric-parameter-contract-coverage.json"),
        help="Path to output coverage JSON report",
    )
    args = parser.parse_args()

    report = generate_coverage_report(args.catalog, args.output)
    print(
        f"Coverage report generated: {args.output} (total: {report['summary']['total_metrics']}, errors: {report['summary']['validation']['errors_count']})"
    )
