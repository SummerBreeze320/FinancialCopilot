package com.financial.copilot.agent.core.dag.planner;

import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.ExecutionGraphSnapshot;

public record GraphPlan(ExecutionGraphSnapshot graph) {
    public ExecutionGraph restore() {
        if (graph == null) throw new IllegalStateException("FINISH_GRAPH requires graph");
        return graph.restore();
    }
}
