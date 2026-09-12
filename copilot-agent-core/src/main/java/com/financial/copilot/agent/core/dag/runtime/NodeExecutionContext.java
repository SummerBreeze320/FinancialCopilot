package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.runtime.context.CancellationToken;

/** Run-scoped services exposed to a node executor. */
public record NodeExecutionContext(
        GraphRunRequest request,
        ArtifactStore artifacts,
        CancellationToken cancellationToken
) {}
