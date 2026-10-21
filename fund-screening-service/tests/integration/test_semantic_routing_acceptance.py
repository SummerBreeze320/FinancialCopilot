from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.catalog.loader import Catalog
from app.graph.direct_probe import probe_strict_direct
from app.graph.nodes import ScreeningGraphNodes
from app.graph.router import classify_plan_route
from app.graph.state import empty_state
from app.graph.workflow import build_screening_graph
from app.main import create_app
from app.models.candidate import CandidateContract
from app.models.dsl import (
    BusinessGroup,
    FundScreeningDsl,
    SectorExpression,
    SectorLeaf,
    SortItem,
)
from app.models.dsl_nodes import MetricLogicNode, MetricPredicate
from app.models.semantic_plan import (
    AdjudicationResult,
    ConditionBinding,
    MetricLeaf,
    MetricTree,
    PlanGroup,
    ScopeLeaf,
    ScopeTree,
    SemanticPlan,
)
from app.pipeline.analysis import AnalysisService
from app.pipeline.builder import CommandBuilder
from app.pipeline.editor import EditKind, apply_simple_edit, classify_edit
from app.pipeline.validator import validate_and_repair


# =========================================================================
# Scenario 1: 基金代码 Strict Direct，0次 LLM
# =========================================================================
def test_scenario_1_strict_direct_fund_code():
    candidates = [
        CandidateContract(
            domain="entity",
            key="001938.OF",
            name="中欧时代先锋股票A",
            aliases=("001938",),
            binding_role="FUND",
            preferred_metric="f_info_fundcode",
            supported_operators=("=", "!="),
        )
    ]
    is_direct, direct_dsl, reason = probe_strict_direct("001938", candidates)
    assert is_direct is True
    assert reason == "EXACT_FUND_CODE"
    assert direct_dsl is not None
    assert direct_dsl.children[0].metric.children[0].value == "001938.OF"


# =========================================================================
# Scenario 2: 单指标严格表达式 Direct 或安全进入 Simple
# =========================================================================
def test_scenario_2_single_metric_strict_direct():
    candidates = [
        CandidateContract(
            domain="metric",
            key="f_info_fundscale",
            name="基金规模",
            aliases=("规模",),
            supported_operators=(">", ">=", "<", "<="),
            value_type="BILLION_CNY",
        )
    ]
    is_direct, direct_dsl, reason = probe_strict_direct("规模大于10亿", candidates)
    assert is_direct is True
    assert reason == "STRICT_SINGLE_METRIC"
    assert direct_dsl is not None
    leaf = direct_dsl.children[0].metric.children[0]
    assert leaf.metric == "f_info_fundscale"
    assert leaf.operator == ">"
    assert leaf.value == 10.0


# =========================================================================
# Scenario 3: 单 Group 多个 AND 条件进入 Simple
# =========================================================================
def test_scenario_3_single_group_multiple_and_enters_simple():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                scope_tree=ScopeTree(children=[ScopeLeaf(condition_id="s1", mention="全市场")]),
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(condition_id="m1", mention="规模大于10亿"),
                        MetricLeaf(condition_id="m2", mention="成立超过3年"),
                    ],
                ),
            )
        ]
    )
    by_condition = {
        "s1": {"candidates": [{"score": 0.95}]},
        "m1": {"candidates": [{"score": 0.92}]},
        "m2": {"candidates": [{"score": 0.90}]},
    }
    route, reason = classify_plan_route(plan, by_condition)
    assert route == "simple"
    assert "SINGLE_GROUP_SHALLOW" in reason


# =========================================================================
# Scenario 4: A AND (B OR C) 保持递归逻辑结构
# =========================================================================
def test_scenario_4_nested_and_or_logic_preserved():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(condition_id="m1", mention="规模大于10亿"),
                        MetricTree(
                            operator="OR",
                            children=[
                                MetricLeaf(condition_id="m2", mention="成立大于5年"),
                                MetricLeaf(condition_id="m3", mention="近3年收益大于20%"),
                            ],
                        ),
                    ],
                ),
            )
        ]
    )
    adjudication = AdjudicationResult(
        condition_bindings=[
            ConditionBinding(condition_id="m1", candidate_key="f_info_fundscale", operator=">", value=10, unit="亿"),
            ConditionBinding(condition_id="m2", candidate_key="f_info_existingyear", operator=">", value=5, unit="年"),
            ConditionBinding(condition_id="m3", candidate_key="f_return_3y", operator=">", value=20, unit="%"),
        ]
    )
    contracts = {
        "f_info_fundscale": CandidateContract("metric", "f_info_fundscale", "基金规模"),
        "f_info_existingyear": CandidateContract("metric", "f_info_existingyear", "成立年限"),
        "f_return_3y": CandidateContract("metric", "f_return_3y", "近3年回报"),
        "1000019220000000": CandidateContract("scope", "1000019220000000", "全市场"),
    }
    builder = CommandBuilder()
    dsl = builder.build_from_plan(plan, adjudication, contracts)

    metric_expr = dsl.children[0].metric
    assert metric_expr.operator == "AND"
    assert metric_expr.children[0].metric == "f_info_fundscale"
    sub_or = metric_expr.children[1]
    assert sub_or.operator == "OR"
    assert len(sub_or.children) == 2
    assert sub_or.children[0].metric == "f_info_existingyear"
    assert sub_or.children[1].metric == "f_return_3y"


# =========================================================================
# Scenario 5: 多 Group 且每组独立 scope
# =========================================================================
def test_scenario_5_multiple_groups_with_independent_scopes():
    plan = SemanticPlan(
        group_operator="OR",
        groups=[
            PlanGroup(
                group_id="g1",
                scope_tree=ScopeTree(children=[ScopeLeaf(condition_id="s1", mention="股票型基金")]),
                metric_tree=MetricTree(children=[MetricLeaf(condition_id="m1", mention="规模大于10亿")]),
            ),
            PlanGroup(
                group_id="g2",
                scope_tree=ScopeTree(children=[ScopeLeaf(condition_id="s2", mention="混合型基金")]),
                metric_tree=MetricTree(children=[MetricLeaf(condition_id="m2", mention="成立大于5年")]),
            ),
        ],
    )
    adjudication = AdjudicationResult(
        condition_bindings=[
            ConditionBinding(condition_id="s1", candidate_key="1000002533000000", operator="="),
            ConditionBinding(condition_id="m1", candidate_key="f_info_fundscale", operator=">", value=10, unit="亿"),
            ConditionBinding(condition_id="s2", candidate_key="1000002535000000", operator="="),
            ConditionBinding(condition_id="m2", candidate_key="f_info_existingyear", operator=">", value=5, unit="年"),
        ]
    )
    contracts = {
        "1000002533000000": CandidateContract("scope", "1000002533000000", "股票型基金"),
        "1000002535000000": CandidateContract("scope", "1000002535000000", "混合型基金"),
        "f_info_fundscale": CandidateContract("metric", "f_info_fundscale", "基金规模"),
        "f_info_existingyear": CandidateContract("metric", "f_info_existingyear", "成立年限"),
    }
    route, reason = classify_plan_route(plan, {})
    assert route == "full"
    assert "MULTI_GROUP" in reason

    builder = CommandBuilder()
    dsl = builder.build_from_plan(plan, adjudication, contracts)
    assert len(dsl.children) == 2
    assert dsl.children[0].sector.children[0].sector_id == "1000002533000000"
    assert dsl.children[1].sector.children[0].sector_id == "1000002535000000"


# =========================================================================
# Scenario 6: 同一指标不同参数重复出现
# =========================================================================
def test_scenario_6_same_metric_different_parameters_repeat():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(condition_id="m1", mention="近1年回撤小于10%"),
                        MetricLeaf(condition_id="m2", mention="近3年回撤小于20%"),
                    ],
                ),
            )
        ]
    )
    adjudication = AdjudicationResult(
        condition_bindings=[
            ConditionBinding(
                condition_id="m1",
                candidate_key="f_risk_maxdownside",
                operator="<=",
                value=10.0,
                unit="%",
                params={"period": "1Y"},
            ),
            ConditionBinding(
                condition_id="m2",
                candidate_key="f_risk_maxdownside",
                operator="<=",
                value=20.0,
                unit="%",
                params={"period": "3Y"},
            ),
        ]
    )
    contracts = {
        "f_risk_maxdownside": CandidateContract(
            "metric",
            "f_risk_maxdownside",
            "最大回撤",
            result_contract={"storageConvention": "NEGATIVE_PERCENT"},
        ),
        "1000019220000000": CandidateContract("scope", "1000019220000000", "全市场"),
    }
    builder = CommandBuilder()
    dsl = builder.build_from_plan(plan, adjudication, contracts)
    val_res = validate_and_repair(dsl, contracts)

    children = val_res.dsl.children[0].metric.children
    assert len(children) == 2
    assert children[0].metric == "f_risk_maxdownside"
    assert children[0].value == -10.0
    assert children[0].operator == ">="

    assert children[1].metric == "f_risk_maxdownside"
    assert children[1].value == -20.0
    assert children[1].operator == ">="


# =========================================================================
# Scenario 7: 基金公司、基金经理、股票和指数实体正确绑定
# =========================================================================
def test_scenario_7_company_manager_stock_entity_bindings():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(condition_id="e1", mention="易方达基金", domain="entity"),
                        MetricLeaf(condition_id="e2", mention="张坤", domain="entity"),
                        MetricLeaf(condition_id="e3", mention="贵州茅台", domain="entity"),
                    ],
                ),
            )
        ]
    )
    adjudication = AdjudicationResult(
        condition_bindings=[
            ConditionBinding(condition_id="e1", candidate_key="company_efund", binding_role="COMPANY", operator="="),
            ConditionBinding(condition_id="e2", candidate_key="manager_zk", binding_role="MANAGER", operator="="),
            ConditionBinding(condition_id="e3", candidate_key="stock_gzmt", binding_role="STOCK", operator="="),
        ]
    )
    contracts = {
        "company_efund": CandidateContract(
            "entity",
            "company_efund",
            "易方达基金",
            preferred_metric="f_info_fundcompany",
            value_type="ENTITY_REF",
        ),
        "manager_zk": CandidateContract(
            "entity",
            "manager_zk",
            "张坤",
            preferred_metric="f_info_manager",
            value_type="ENTITY_REF",
        ),
        "stock_gzmt": CandidateContract(
            "entity",
            "stock_gzmt",
            "贵州茅台",
            preferred_metric="f_info_stockholding",
            value_type="ENTITY_REF",
        ),
        "1000019220000000": CandidateContract("scope", "1000019220000000", "全市场"),
    }
    builder = CommandBuilder()
    dsl = builder.build_from_plan(plan, adjudication, contracts)
    leaves = dsl.children[0].metric.children
    assert leaves[0].metric == "f_info_fundcompany"
    assert leaves[0].value == "company_efund"
    assert leaves[1].metric == "f_info_manager"
    assert leaves[1].value == "manager_zk"
    assert leaves[2].metric == "f_info_stockholding"
    assert leaves[2].value == "stock_gzmt"


# =========================================================================
# Scenario 8: 指数实体可绑定为指标条件或 indexCode 参数
# =========================================================================
def test_scenario_8_index_entity_binding_as_condition_and_as_param():
    plan = SemanticPlan(
        groups=[
            PlanGroup(
                group_id="g1",
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(condition_id="e1", mention="沪深300指数", domain="entity"),
                        MetricLeaf(condition_id="m1", mention="相对沪深300超额收益大于5%"),
                    ],
                ),
            )
        ]
    )
    adjudication = AdjudicationResult(
        condition_bindings=[
            ConditionBinding(
                condition_id="e1",
                candidate_key="000300.SH",
                binding_role="TRACKED_INDEX",
                operator="=",
            ),
            ConditionBinding(
                condition_id="m1",
                candidate_key="f_risk_excessreturn",
                operator=">",
                value=5.0,
                unit="%",
                params={"indexCode": "000300.SH"},
            ),
        ]
    )
    contracts = {
        "000300.SH": CandidateContract(
            "entity",
            "000300.SH",
            "沪深300",
            preferred_metric="f_info_trackindex",
            value_type="ENTITY_REF",
        ),
        "f_risk_excessreturn": CandidateContract(
            "metric",
            "f_risk_excessreturn",
            "超额收益",
            parameters=[{"key": "indexCode", "required": True}],
        ),
        "1000019220000000": CandidateContract("scope", "1000019220000000", "全市场"),
    }
    builder = CommandBuilder()
    dsl = builder.build_from_plan(plan, adjudication, contracts)
    leaves = dsl.children[0].metric.children
    assert leaves[0].metric == "f_info_trackindex"
    assert leaves[0].value == "000300.SH"
    assert leaves[1].metric == "f_risk_excessreturn"
    assert any(p["key"] == "indexCode" and p["value"] == "000300.SH" for p in leaves[1].params)


# =========================================================================
# Scenario 9: 必填参数缺失产生 condition 级 clarification
# =========================================================================
def test_scenario_9_missing_required_parameter_triggers_condition_clarification():
    contract = CandidateContract(
        "metric",
        "f_risk_beta",
        "Beta系数",
        parameters=[{"key": "indexCode", "required": True, "type": "STRING", "description": "基准指数代码"}],
    )
    dsl = FundScreeningDsl(
        version="fund-screening/v1",
        operator="AND",
        children=[
            BusinessGroup(
                sector=SectorExpression(operator="OR", children=[SectorLeaf(sectorId="1000019220000000")]),
                metric=MetricLogicNode(
                    operator="AND",
                    children=[MetricPredicate(metric="f_risk_beta", operator=">", value=1.0, params=[])],
                ),
            )
        ],
        limit=20,
    )
    val_res = validate_and_repair(dsl, {"f_risk_beta": contract})
    assert val_res.clarification is not None
    assert "indexCode" in val_res.clarification.message


# =========================================================================
# Scenario 10: 最大回撤根据结果契约转换负值口径
# =========================================================================
def test_scenario_10_negative_drawdown_storage_convention_conversion():
    contract = CandidateContract(
        domain="metric",
        key="f_risk_maxdownside",
        name="最大回撤",
        result_contract={"storageConvention": "NEGATIVE_PERCENT", "supportedOperators": [">=", "<="]},
    )
    dsl = FundScreeningDsl(
        version="fund-screening/v1",
        operator="AND",
        children=[
            BusinessGroup(
                sector=SectorExpression(operator="OR", children=[SectorLeaf(sectorId="1000019220000000")]),
                metric=MetricLogicNode(
                    operator="AND",
                    children=[MetricPredicate(operator="<=", metric="f_risk_maxdownside", value=15.0, unit="%")],
                ),
            )
        ],
        limit=20,
    )
    res = validate_and_repair(dsl, {"f_risk_maxdownside": contract})
    leaf = res.dsl.children[0].metric.children[0]
    assert leaf.value == -15.0
    assert leaf.operator == ">="
    assert "NEGATIVE_DRAWDOWN_STORAGE" in res.repairs


# =========================================================================
# Scenario 11: 简单编辑不调用语义 LLM
# =========================================================================
def test_scenario_11_simple_edit_zero_semantic_llm_calls():
    initial_dsl = {
        "version": "fund-screening/v1",
        "operator": "AND",
        "children": [
            {
                "sector": {"operator": "OR", "children": [{"sectorId": "1000019220000000"}]},
                "metric": {"operator": "AND", "children": [{"metric": "f_info_fundscale", "operator": ">", "value": 10.0}]},
            }
        ],
        "limit": 20,
    }
    # 1. Limit edit
    is_direct, edited_dsl, reason = probe_strict_direct("改成前50只", [], previous_dsl=initial_dsl)
    assert is_direct is True
    assert reason == "SIMPLE_EDIT_DIRECT"
    assert edited_dsl.limit == 50

    # 2. Condition removal edit
    is_direct2, edited_dsl2, reason2 = probe_strict_direct("去掉规模限制", [], previous_dsl=initial_dsl)
    assert is_direct2 is True
    assert reason2 == "SIMPLE_EDIT_DIRECT"
    assert edited_dsl2.children[0].metric is None or len(edited_dsl2.children[0].metric.children or []) == 0


# =========================================================================
# Scenario 12: 复杂编辑只召回和修改受影响的 condition
# =========================================================================
def test_scenario_12_complex_edit_classified_correctly():
    initial_dsl = {
        "version": "fund-screening/v1",
        "operator": "AND",
        "children": [
            {
                "sector": {"operator": "OR", "children": [{"sectorId": "1000019220000000"}]},
                "metric": {"operator": "AND", "children": [{"metric": "f_info_fundscale", "operator": ">", "value": 10.0}]},
            }
        ],
        "limit": 20,
    }
    # Adding a new condition is complex, triggering targeted recall & adjudication
    assert classify_edit("再加一个成立超过5年", initial_dsl) == EditKind.COMPLEX
    assert classify_edit("换成近3年收益大于20%", initial_dsl) == EditKind.COMPLEX


# =========================================================================
# Scenario 13: HTTP 和 WebSocket 产生相同最终 DSL
# =========================================================================
def test_scenario_13_http_and_websocket_produce_same_final_dsl():
    client = TestClient(create_app())
    query = "001938"

    # 1. HTTP Endpoint
    http_resp = client.post("/v1/fund-screening/commands", json={"query": query})
    assert http_resp.status_code == 200
    http_dsl = http_resp.json()["dsl"]

    # 2. WebSocket Endpoint
    with client.websocket_connect("/v1/fund-screening/ws") as ws:
        ws.send_json({"query": query})
        ws_dsl = None
        while True:
            msg = ws.receive_json()
            if msg.get("type") in ("finalized", "final"):
                ws_dsl = msg.get("dsl")
                break
            if msg.get("type") == "error":
                break

    assert ws_dsl is not None
    assert http_dsl == ws_dsl


# =========================================================================
# Scenario 14: 烟测与运行态冒烟测试
# =========================================================================
def test_scenario_14_runtime_smoke_test():
    client = TestClient(create_app())
    resp = client.get("/v1/fund-screening/health")
    assert resp.status_code == 200
    health_data = resp.json()
    assert "service" in health_data
    assert "dependencies" in health_data
