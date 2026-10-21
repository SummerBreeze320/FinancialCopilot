from __future__ import annotations

from typing import Any
from langgraph.graph import END, START, StateGraph

from app.graph.nodes import ScreeningGraphNodes
from app.graph.repair import clarification_node, decide_validation_outcome, mechanical_repair_node
from app.graph.state import FundScreeningState


def build_screening_graph(nodes: ScreeningGraphNodes, checkpointer: Any = None):
    builder = StateGraph(FundScreeningState)

    # 1. 基础阶段节点
    builder.add_node("query_preprocess", nodes.query_preprocess)
    builder.add_node("strict_direct_probe", nodes.strict_direct_probe)
    builder.add_node("direct_build", nodes.direct_build)
    builder.add_node("simple_edit", nodes.simple_edit)
    builder.add_node("semantic_plan", nodes.semantic_plan)
    builder.add_node("recall_by_condition", nodes.recall_by_condition)
    builder.add_node("simple_full_router", nodes.simple_full_router)
    builder.add_node("simple_adjudication", nodes.simple_adjudication)
    builder.add_node("full_adjudication", nodes.full_adjudication)
    builder.add_node("command_build", nodes.command_build)
    builder.add_node("validator", nodes.validator)
    builder.add_node("mechanical_repair", mechanical_repair_node)
    builder.add_node("clarification", clarification_node)
    builder.add_node("command_finalize", nodes.command_finalize)

    # 2. 起始与 StrictDirectProbe 分流
    builder.add_edge(START, "query_preprocess")
    builder.add_edge("query_preprocess", "strict_direct_probe")

    def direct_probe_condition(state: FundScreeningState) -> str:
        if state.get("intent") == "EDIT" and state.get("edit_complexity") == "SIMPLE" and state.get("previous_dsl"):
            return "simple_edit"
        if state.get("route") == "direct":
            return "direct_build"
        return "semantic_plan"

    builder.add_conditional_edges(
        "strict_direct_probe",
        direct_probe_condition,
        {
            "simple_edit": "simple_edit",
            "direct_build": "direct_build",
            "semantic_plan": "semantic_plan",
        },
    )

    # 3. 语义规划 -> Condition 级召回 -> Simple/Full 路由
    builder.add_edge("semantic_plan", "recall_by_condition")
    builder.add_edge("recall_by_condition", "simple_full_router")

    def router_condition(state: FundScreeningState) -> str:
        return "simple_adjudication" if state.get("route") == "simple" else "full_adjudication"

    builder.add_conditional_edges(
        "simple_full_router",
        router_condition,
        {
            "simple_adjudication": "simple_adjudication",
            "full_adjudication": "full_adjudication",
        },
    )

    # 4. 裁决 -> 确定性 AST CommandBuild
    builder.add_edge("simple_adjudication", "command_build")
    builder.add_edge("full_adjudication", "command_build")

    # 5. 各分支汇入 validator
    builder.add_edge("simple_edit", "validator")
    builder.add_edge("direct_build", "validator")
    builder.add_edge("command_build", "validator")

    # 6. 校验与循环修复条件边
    def validation_condition(state: FundScreeningState) -> str:
        res = decide_validation_outcome(state)
        if res == "finalize":
            return "command_finalize"
        if res == "repair":
            return "mechanical_repair"
        return "clarification"

    builder.add_conditional_edges(
        "validator",
        validation_condition,
        {
            "command_finalize": "command_finalize",
            "mechanical_repair": "mechanical_repair",
            "clarification": "clarification",
        },
    )
    builder.add_edge("mechanical_repair", "validator")

    # 7. 终态边
    builder.add_edge("clarification", END)
    builder.add_edge("command_finalize", END)

    return builder.compile(checkpointer=checkpointer)
