package com.financial.copilot.agent.core.infra.dag.runtime;

import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.event.NodeEventBus;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.NodeStatus;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class DagRunContext {
    final GraphRunRequest request;
    final ExecutionGraph graph;
    final ArtifactStore artifacts = new ArtifactStore();
    final Map<String, NodeStatus> statuses = new ConcurrentHashMap<>();
    final CancellationToken cancellation;
    final NodeEventBus events;
    final Instant startedAt = Instant.now();

    DagRunContext(GraphRunRequest request, ExecutionGraph graph) {
        this.request = request;
        this.graph = graph;
        this.cancellation = new CancellationToken(request.runId());
        this.events = new NodeEventBus(cancellation);
    }

    GraphRunResult result() {
        return new GraphRunResult(request.runId(), graph, statuses,
                artifacts.getAllArtifacts(), startedAt, Instant.now());
    }
}
