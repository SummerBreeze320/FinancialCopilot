import json
from pathlib import Path
import pytest

from app.catalog.loader import Catalog
from app.models.candidate import CandidateContract
from app.models.semantic_plan import (
    SemanticPlan,
    PlanGroup,
    MetricTree,
    MetricLeaf,
    AdjudicationResult,
    ConditionBinding,
)
from app.pipeline.builder import CommandBuilder
from app.pipeline.validator import validate_and_repair


@pytest.fixture(scope="module")
def catalog():
    service_root = Path(__file__).resolve().parents[2]
    metrics_path = service_root.parent / "docs" / "temp" / "metrics.json"
    sectors_path = service_root / "tests" / "fixtures" / "sectors-small.json"
    entities_path = service_root / "tests" / "fixtures" / "entities-small.json"
    return Catalog.from_files(metrics_path, sectors_path, entities_path)


@pytest.fixture(scope="module")
def contracts_map(catalog):
    return {m.key: m for m in catalog.metrics}


def test_executable_dsl_contract_for_scale_and_years(contracts_map):
    query = "规模大于10亿且成立超过5年"
    as_of_date = "20260930"

    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(condition_id="m1", mention="规模大于10亿"),
                        MetricLeaf(condition_id="m2", mention="成立超过5年"),
                    ],
                ),
            )
        ]
    )

    adjudication = AdjudicationResult(
        condition_bindings=[
            ConditionBinding(
                condition_id="m1",
                candidate_key="f_netasset_total",
                operator=">",
                value=1000000000,
                unit="元",
            ),
            ConditionBinding(
                condition_id="m2",
                candidate_key="f_info_existingyear",
                operator=">",
                value=5,
                unit="年",
            ),
        ]
    )

    contracts = {
        "f_netasset_total": contracts_map.get("f_netasset_total")
        or CandidateContract("metric", "f_netasset_total", "资产净值合计"),
        "f_info_existingyear": contracts_map.get("f_info_existingyear")
        or CandidateContract("metric", "f_info_existingyear", "成立年限"),
        "1000019220000000": CandidateContract("scope", "1000019220000000", "全部公募基金"),
    }

    builder = CommandBuilder()
    draft_dsl = builder.build_from_plan(plan, adjudication, contracts)

    # 经过校验与动态解析（传入 as_of_date）
    # 如果接口尚未支持 as_of_date，直接校验
    try:
        val_res = validate_and_repair(draft_dsl, contracts, as_of_date=as_of_date)
    except TypeError:
        val_res = validate_and_repair(draft_dsl, contracts)

    dsl = val_res.dsl
    raw_dict = dsl.model_dump(by_alias=True)

    # 1. 逻辑节点不得序列化叶子字段 (params, value, unit)
    metric_group_dump = dsl.children[0].metric.model_dump()
    assert metric_group_dump.get("params") is None

    # 2. 指标叶子数值与单位业务化 (元/年，非 DECIMAL)
    leaf0 = dsl.children[0].metric.children[0]
    assert leaf0.value == 1_000_000_000
    assert leaf0.unit == "元"

    leaf1 = dsl.children[0].metric.children[1]
    assert leaf1.value == 5
    assert leaf1.unit == "年"

    # 3. 动态日期占位符必须被解析为具体 yyyyMMdd (不得残留 LATEST_)
    assert all(
        p.get("value") == as_of_date
        for leaf in dsl.children[0].metric.children
        for p in (leaf.params if isinstance(leaf.params, list) else [])
    )
    assert "LATEST_" not in json.dumps(raw_dict, ensure_ascii=False)

    # 4. Sector 叶子只输出 sectorId，不得包含 match
    sector_children = dsl.children[0].sector.model_dump(by_alias=True)["children"]
    assert sector_children == [{"sectorId": "1000019220000000"}]

    # 5. 双层 sort 与 limit (Group 级与 Root 级) 均完整且非空
    assert dsl.children[0].sort
    assert dsl.children[0].limit == 20
    assert dsl.sort
    assert dsl.limit == 20
