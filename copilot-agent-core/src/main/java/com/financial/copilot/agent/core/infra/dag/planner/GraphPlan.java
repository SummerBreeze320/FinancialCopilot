package com.financial.copilot.agent.core.infra.dag.planner;

import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraphSnapshot;

public record GraphPlan(ExecutionGraphSnapshot graph) {
    public ExecutionGraph restore() {
        if (graph == null) throw new IllegalStateException("FINISH_GRAPH requires graph");
        return graph.restore();
    }
}
