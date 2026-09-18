package com.financial.copilot.agent.core.infra.dag.model;

import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.runtime.resource.NodePriority;
import com.financial.copilot.agent.core.infra.dag.runtime.resource.ResourceType;

import java.util.*;

/** Jackson-friendly immutable representation of a graph. */
public record ExecutionGraphSnapshot(
        String graphId, int revision, List<NodeSnapshot> nodes, List<EdgeSnapshot> edges
) {
    public ExecutionGraphSnapshot {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static ExecutionGraphSnapshot from(ExecutionGraph graph) {
        List<NodeSnapshot> nodeSnapshots = graph.getNodes().values().stream().map(NodeSnapshot::from).toList();
        List<EdgeSnapshot> edgeSnapshots = new ArrayList<>();
        graph.getNodes().keySet().forEach(from -> graph.getDownstream(from)
                .forEach(to -> edgeSnapshots.add(new EdgeSnapshot(from, to))));
        return new ExecutionGraphSnapshot(graph.getGraphId(), graph.getRevision(), nodeSnapshots, edgeSnapshots);
    }

    public ExecutionGraph restore() {
        ExecutionGraph graph = new ExecutionGraph(graphId);
        nodes.forEach(node -> graph.addNode(node.restore()));
        edges.forEach(edge -> graph.addEdge(edge.from(), edge.to()));
        graph.restoreRevision(revision);
        return graph;
    }

    public record NodeSnapshot(
            String nodeId, String taskType, String name, Set<ArtifactType> requiredInputs,
            List<InputBinding> inputBindings, ArtifactType outputType, Map<String, Object> params,
            long timeoutMillis, FailurePolicy failurePolicy, int maxRetries,
            Map<ResourceType, Integer> resourceRequirements, NodePriority priority,
            boolean skipped, int retryCount
    ) {
        public static NodeSnapshot from(GraphNode node) {
            return new NodeSnapshot(node.getNodeId(), node.getTaskType(), node.getName(), node.getRequiredInputs(),
                    node.getInputBindings(), node.getOutputType(), node.getParams(), node.getTimeout().toMillis(),
                    node.getFailurePolicy(), node.getMaxRetries(), node.getResourceRequirements(), node.getPriority(),
                    node.isSkipped(), node.getRetryCount());
        }

        public GraphNode restore() {
            GraphNode node = GraphNode.builder().nodeId(nodeId).taskType(taskType).name(name)
                    .requiredInputs(requiredInputs).inputBindings(inputBindings).outputType(outputType).params(params)
                    .timeout(java.time.Duration.ofMillis(timeoutMillis)).failurePolicy(failurePolicy)
                    .maxRetries(maxRetries).resourceRequirements(resourceRequirements).priority(priority).build();
            if (skipped) node.markSkipped();
            for (int i = 0; i < retryCount; i++) node.incrementRetryCount();
            return node;
        }
    }

    public record EdgeSnapshot(String from, String to) {}
}
