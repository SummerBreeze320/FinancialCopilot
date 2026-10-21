from __future__ import annotations

import os
from pathlib import Path
from app.catalog.defaults import DEFAULT_ENTITIES, DEFAULT_METRICS, DEFAULT_SCOPES
from app.catalog.loader import Catalog
from app.evaluation.generator import (
    export_cases_to_jsonl,
    generate_entity_cases,
    generate_composite_scenario_cases,
    generate_metric_cases,
    generate_scenario_cases,
    generate_sector_cases,
)
from app.evaluation.schema import ExpectedCondition, GoldenCase


def main() -> None:
    service_root = Path(__file__).resolve().parents[2]
    golden_dir = service_root / "data" / "golden"
    golden_dir.mkdir(parents=True, exist_ok=True)

    metrics_path = service_root.parent / "docs" / "temp" / "metrics.json"
    sectors_path = service_root.parent / "docs" / "temp" / "sectors.json"
    if not sectors_path.exists():
        sectors_path = service_root / "tests" / "fixtures" / "sectors-small.json"
    entities_path = service_root / "tests" / "fixtures" / "entities-document-small.json"
    if not entities_path.exists():
        entities_path = service_root / "tests" / "fixtures" / "entities-small.json"

    if metrics_path.exists() and sectors_path.exists() and entities_path.exists():
        catalog = Catalog.from_files(metrics_path, sectors_path, entities_path)
        metrics = catalog.metrics
        sectors = catalog.scopes
        entities = catalog.entities
    else:
        metrics = DEFAULT_METRICS
        sectors = DEFAULT_SCOPES
        entities = DEFAULT_ENTITIES

    # 1. Metrics
    metric_cases = generate_metric_cases(metrics)
    export_cases_to_jsonl(metric_cases, golden_dir / "metrics.jsonl")

    # 2. Sectors (Top 100)
    sector_cases = generate_sector_cases(sectors, top_n=100)
    export_cases_to_jsonl(sector_cases, golden_dir / "sectors-top100.jsonl")

    # 3. Entities (Top 100)
    entity_cases = generate_entity_cases(entities, top_n=100)
    export_cases_to_jsonl(entity_cases, golden_dir / "entities-top100.jsonl")

    # 4. Scenarios
    scenario_cases = generate_scenario_cases()
    export_cases_to_jsonl(scenario_cases, golden_dir / "research-scenarios.jsonl")

    composite_cases = generate_composite_scenario_cases()
    export_cases_to_jsonl(composite_cases, golden_dir / "composite-research-scenarios.jsonl")

    # 5. Regressions (Initial scrubbed real query cases)
    regression_cases = [
        GoldenCase(
            id="regression-drawdown-negative-percent",
            category="regression",
            query="最大回撤低于20%",
            difficulty="clear",
            expected_outcome="DSL",
            expected_conditions=[
                ExpectedCondition(
                    mention="最大回撤低于20%",
                    domain="metric",
                    relevant_candidate_keys=["f_risk_maxdownside"],
                    primary_candidate_key="f_risk_maxdownside",
                )
            ],
            expected_dsl={
                "version": "fund-screening/v1",
                "operator": "AND",
                "children": [
                    {
                        "metric": {
                            "operator": "AND",
                            "children": [
                                {"metric": "f_risk_maxdownside", "operator": ">=", "value": -20.0, "unit": "%"}
                            ],
                        }
                    }
                ],
                "limit": 20,
            },
        ),
        GoldenCase(
            id="regression-vague-low-risk-ambiguity",
            category="regression",
            query="找一只低风险的稳健基金",
            difficulty="ambiguous",
            expected_outcome="CLARIFICATION",
            expected_conditions=[
                ExpectedCondition(
                    mention="低风险",
                    domain="metric",
                    relevant_candidate_keys=["f_risk_maxdownside", "f_risk_stdev_1y"],
                )
            ],
            expected_clarification={
                "code": "AMBIGUOUS_METRIC",
                "message": "请明确低风险的具体指标要求（如最大回撤或波动率）",
            },
        ),
    ]
    export_cases_to_jsonl(regression_cases, golden_dir / "regressions.jsonl")

    print(f"Generated golden dataset in {golden_dir}:")
    print(f"  - metrics: {len(metric_cases)}")
    print(f"  - sectors: {len(sector_cases)}")
    print(f"  - entities: {len(entity_cases)}")
    print(f"  - scenarios: {len(scenario_cases)}")
    print(f"  - composite scenarios: {len(composite_cases)}")
    print(f"  - regressions: {len(regression_cases)}")


if __name__ == "__main__":
    main()
