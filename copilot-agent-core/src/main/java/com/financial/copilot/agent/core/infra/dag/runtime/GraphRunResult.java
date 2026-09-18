package com.financial.copilot.agent.core.infra.dag.runtime;

import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.NodeStatus;

import java.time.Instant;
import java.util.Map;

public record GraphRunResult(
        String runId,
        ExecutionGraph graph,
        Map<String, NodeStatus> nodeStatuses,
        Map<String, Artifact<?>> artifacts,
        Instant startedAt,
        Instant completedAt
) {
    public GraphRunResult {
        nodeStatuses = Map.copyOf(nodeStatuses);
        artifacts = Map.copyOf(artifacts);
    }
}
