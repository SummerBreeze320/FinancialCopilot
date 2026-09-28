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
    builder.add_node("route_classifier", nodes.route_classifier)
    builder.add_node("direct_build", nodes.direct_build)
    builder.add_node("simple_semantic_parse", nodes.simple_semantic_parse)
    builder.add_node("pre_recall_plan", nodes.pre_recall_plan)
    builder.add_node("parallel_recall", nodes.parallel_recall)
    builder.add_node("candidate_adjudication", nodes.candidate_adjudication)
    builder.add_node("command_build", nodes.command_build)
    builder.add_node("validator", nodes.validator)
    builder.add_node("mechanical_repair", mechanical_repair_node)
    builder.add_node("clarification", clarification_node)
    builder.add_node("command_finalize", nodes.command_finalize)

    # 2. 起始流
    builder.add_edge(START, "query_preprocess")
    builder.add_edge("query_preprocess", "route_classifier")

    # 3. 三级条件路由
    def route_condition(state: FundScreeningState) -> str:
        r = state.get("route", "full")
        if r == "direct":
            return "direct_build"
        if r == "simple":
            return "simple_semantic_parse"
        return "pre_recall_plan"

    builder.add_conditional_edges(
        "route_classifier",
        route_condition,
        {
            "direct_build": "direct_build",
            "simple_semantic_parse": "simple_semantic_parse",
            "pre_recall_plan": "pre_recall_plan",
        },
    )

    # 4. full 路径串联
    builder.add_edge("pre_recall_plan", "parallel_recall")
    builder.add_edge("parallel_recall", "candidate_adjudication")
    builder.add_edge("candidate_adjudication", "command_build")

    # 5. 各分支汇入 validator
    builder.add_edge("direct_build", "validator")
    builder.add_edge("simple_semantic_parse", "validator")
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
