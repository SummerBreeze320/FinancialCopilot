package com.financial.copilot.agent.core.dag.runtime.checkpoint;

import com.financial.copilot.agent.core.dag.model.NodeStatus;

import java.time.Instant;
import java.util.Map;

/**
 * <h1>DAG 执行状态持久化快照 (Dag Checkpoint)</h1>
 */
public record DagCheckpoint(
    String runId,
    String sessionId,
    int revision,
    Map<String, NodeStatus> nodeStatuses,
    Map<String, String> artifactIds,
    Map<String, Object> serializedPayloads,
    Instant checkpointTime
) {
    public DagCheckpoint {
        nodeStatuses = nodeStatuses != null ? Map.copyOf(nodeStatuses) : Map.of();
        artifactIds = artifactIds != null ? Map.copyOf(artifactIds) : Map.of();
        serializedPayloads = serializedPayloads != null ? Map.copyOf(serializedPayloads) : Map.of();
        checkpointTime = checkpointTime != null ? checkpointTime : Instant.now();
    }

    public boolean isNodeCompleted(String nodeId) {
        NodeStatus status = nodeStatuses.get(nodeId);
        return status == NodeStatus.SUCCEEDED || status == NodeStatus.SKIPPED;
    }
}
