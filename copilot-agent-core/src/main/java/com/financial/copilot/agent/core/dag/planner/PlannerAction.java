package com.financial.copilot.agent.core.dag.planner;

public record PlannerAction(Action action, String query, GraphPlan plan) {
    public enum Action {
        USE_METRIC_RAG, USE_SKILL_REGISTRY, USE_CAPABILITY_REGISTRY,
        SEARCH_DOCUMENTS, READ_MEMORY, FINISH_GRAPH
    }
}
