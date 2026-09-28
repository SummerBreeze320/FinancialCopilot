#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# Add project root to sys.path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.catalog.parameter_rules import infer_aliases, infer_parameters, infer_value_type
from app.models.parameters import MetricParameterDefinition


def enrich_catalog(input_path: Path, output_path: Path, report_path: Path | None = None) -> dict:
    with open(input_path, "r", encoding="utf-8") as f:
        metrics = json.load(f)

    total_count = len(metrics)
    by_param_count: dict[str, int] = {}
    by_prefix: dict[str, int] = {}
    param_type_counts: dict[str, int] = {}

    enriched_metrics = []
    for m in metrics:
        inferred = infer_parameters(m)
        validated_params = [
            MetricParameterDefinition.model_validate(p).model_dump(by_alias=True, exclude_none=True)
            for p in inferred
        ]
        m["parameters"] = validated_params
        m["value_type"] = infer_value_type(m)
        m["aliases"] = infer_aliases(m)
        enriched_metrics.append(m)

        p_count_key = str(len(validated_params))
        by_param_count[p_count_key] = by_param_count.get(p_count_key, 0) + 1

        prefix = m.get("mnemonic", "").split("_")[1] if "_" in m.get("mnemonic", "") else "other"
        by_prefix[prefix] = by_prefix.get(prefix, 0) + 1

        for p in validated_params:
            vt = p.get("valueType", "UNKNOWN")
            param_type_counts[vt] = param_type_counts.get(vt, 0) + 1

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(enriched_metrics, f, ensure_ascii=False, indent=2)

    coverage_report = {
        "total_metrics": total_count,
        "enriched_metrics": len(enriched_metrics),
        "coverage_rate": 1.0 if total_count > 0 else 0.0,
        "by_parameter_count": by_param_count,
        "by_prefix": by_prefix,
        "by_parameter_type": param_type_counts,
    }

    if report_path:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        with open(report_path, "w", encoding="utf-8") as f:
            json.dump(coverage_report, f, ensure_ascii=False, indent=2)

    return coverage_report


def main() -> None:
    parser = argparse.ArgumentParser(description="Enrich metrics.json with parameter contracts and generate coverage report")
    parser.add_argument("input", nargs="?", default="../docs/temp/metrics.json", help="Path to input metrics.json")
    parser.add_argument("output", nargs="?", default="../docs/temp/metrics.json", help="Path to output metrics.json")
    parser.add_argument("--report", default="../docs/temp/metric-parameter-coverage.json", help="Path to coverage report")
    args = parser.parse_args()

    input_path = Path(args.input).resolve()
    output_path = Path(args.output).resolve()
    report_path = Path(args.report).resolve() if args.report else None

    print(f"Reading from: {input_path}")
    report = enrich_catalog(input_path, output_path, report_path)
    print(f"Enriched {report['enriched_metrics']}/{report['total_metrics']} metrics ({report['coverage_rate'] * 100:.1f}%)")
    print(f"Written to: {output_path}")
    if report_path:
        print(f"Report written to: {report_path}")


if __name__ == "__main__":
    main()
