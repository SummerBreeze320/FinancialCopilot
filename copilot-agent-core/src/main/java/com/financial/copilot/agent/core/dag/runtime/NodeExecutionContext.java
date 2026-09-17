package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.event.NodeEventBus;
import com.financial.copilot.agent.core.dag.runtime.context.CancellationToken;

/** Run-scoped services exposed to a node executor. */
public record NodeExecutionContext(
        GraphRunRequest request,
        String nodeId,
        ArtifactStore artifacts,
        CancellationToken cancellationToken,
        NodeEventBus eventBus
) {
    public NodeExecutionContext(GraphRunRequest request,
                                String nodeId,
                                ArtifactStore artifacts,
                                CancellationToken cancellationToken) {
        this(request, nodeId, artifacts, cancellationToken, null);
    }

    public void publishWorkspace(Object workspace) {
        if (eventBus != null && workspace != null) {
            String runId = request != null ? request.runId() : "";
            eventBus.publishWorkspace(runId, nodeId, workspace);
        }
    }
}
