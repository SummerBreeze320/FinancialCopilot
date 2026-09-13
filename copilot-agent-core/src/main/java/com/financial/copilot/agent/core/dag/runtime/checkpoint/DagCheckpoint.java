package com.financial.copilot.agent.core.dag.runtime.checkpoint;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.model.ExecutionGraphSnapshot;
import com.financial.copilot.agent.core.dag.model.NodeStatus;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;

import java.time.Instant;
import java.util.Map;

/**
 * Complete durable state needed to resume one owned graph run.
 */
public record DagCheckpoint(
    String runId,
    Long userId,
    String sessionId,
    String prompt,
    boolean enableThinking,
    UserInvestmentProfile profile,
    ExecutionGraphSnapshot graph,
    Map<String, NodeStatus> nodeStatuses,
    Map<String, Artifact<?>> artifacts,
    Instant savedAt
) {
    public DagCheckpoint {
        prompt = prompt != null ? prompt : "";
        nodeStatuses = nodeStatuses != null ? Map.copyOf(nodeStatuses) : Map.of();
        artifacts = artifacts != null ? Map.copyOf(artifacts) : Map.of();
        savedAt = savedAt != null ? savedAt : Instant.now();
    }

    public boolean isNodeCompleted(String nodeId) {
        NodeStatus status = nodeStatuses.get(nodeId);
        return status == NodeStatus.SUCCEEDED || status == NodeStatus.SKIPPED;
    }
}
