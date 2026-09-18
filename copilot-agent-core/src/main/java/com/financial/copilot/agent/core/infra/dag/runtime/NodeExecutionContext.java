package com.financial.copilot.agent.core.infra.dag.runtime;

import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;

/** Run-scoped services exposed to a node executor. */
public record NodeExecutionContext(
        GraphRunRequest request,
        String nodeId,
        ArtifactStore artifacts,
        CancellationToken cancellationToken
) {}
