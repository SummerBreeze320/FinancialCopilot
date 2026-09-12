package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.FailurePolicy;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.model.NodeStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DagRunIsolationTest {

    @Test
    void concurrentRunsKeepQueuesAndArtifactsIsolated() throws Exception {
        DagRuntime runtime = new DagRuntime((node, store, token) -> {
            Thread.sleep(20);
            return Artifact.of("art-" + node.getNodeId(), ArtifactType.GENERAL,
                    node.getNodeId(), node.getNodeId());
        });

        GraphRunHandle first = runtime.run(request("run-a"), graph("A", Duration.ofSeconds(1)));
        GraphRunHandle second = runtime.run(request("run-b"), graph("B", Duration.ofSeconds(1)));

        assertEquals(Set.of("A"), first.completion().get(2, TimeUnit.SECONDS).artifacts().keySet());
        assertEquals(Set.of("B"), second.completion().get(2, TimeUnit.SECONDS).artifacts().keySet());
    }

    @Test
    void emptyGraphCompletesImmediately() throws Exception {
        DagRuntime runtime = new DagRuntime((node, store, token) -> fail("No node should execute"));
        GraphRunResult result = runtime.run(request("empty"), new ExecutionGraph("empty"))
                .completion().get(500, TimeUnit.MILLISECONDS);
        assertTrue(result.artifacts().isEmpty());
        assertTrue(result.nodeStatuses().isEmpty());
    }

    @Test
    void nodeTimeoutProducesTerminalTimeoutStatus() throws Exception {
        DagRuntime runtime = new DagRuntime((node, store, token) -> {
            Thread.sleep(5_000);
            return Artifact.of("late", ArtifactType.GENERAL, node.getNodeId(), "late");
        });
        GraphRunResult result = runtime.run(request("timeout"), graph("slow", Duration.ofMillis(30)))
                .completion().get(2, TimeUnit.SECONDS);
        assertEquals(NodeStatus.TIMEOUT, result.nodeStatuses().get("slow"));
    }

    @Test
    void continuePolicyTimeoutDoesNotStrandDownstreamNodes() throws Exception {
        DagRuntime runtime = new DagRuntime((node, store, token) -> {
            if ("slow".equals(node.getNodeId())) {
                Thread.sleep(5_000);
            }
            return Artifact.of("art-" + node.getNodeId(), ArtifactType.GENERAL,
                    node.getNodeId(), node.getNodeId());
        });
        ExecutionGraph graph = graph("slow", Duration.ofMillis(30));
        graph.addNode(GraphNode.builder().nodeId("report").taskType("SYNTHESIS")
                .failurePolicy(FailurePolicy.FAIL_FAST).timeout(Duration.ofSeconds(1)).build());
        graph.addEdge("slow", "report");

        GraphRunResult result = runtime.run(request("timeout-chain"), graph)
                .completion().get(2, TimeUnit.SECONDS);

        assertEquals(NodeStatus.TIMEOUT, result.nodeStatuses().get("slow"));
        assertEquals(NodeStatus.SUCCEEDED, result.nodeStatuses().get("report"));
    }

    private static GraphRunRequest request(String runId) {
        return new GraphRunRequest(runId, 7L, "session", "prompt", false, null, null, RunMode.SYNC);
    }

    private static ExecutionGraph graph(String nodeId, Duration timeout) {
        ExecutionGraph graph = new ExecutionGraph("graph-" + nodeId);
        graph.addNode(GraphNode.builder().nodeId(nodeId).taskType("GENERAL")
                .failurePolicy(FailurePolicy.CONTINUE).timeout(timeout).build());
        return graph;
    }
}
