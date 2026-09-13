package com.financial.copilot.agent.core.dag.runtime.checkpoint;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.guard.DefaultNodeQualityGate;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.FailurePolicy;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.*;
import com.financial.copilot.agent.core.dag.runtime.resource.ResourceManager;
import com.financial.copilot.agent.core.dag.model.ExecutionGraphSnapshot;
import com.financial.copilot.agent.core.dag.model.NodeStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DagCheckpointRoundTripTest {

    @Test
    void checkpointIsJacksonRoundTrippable() throws Exception {
        ExecutionGraph graph = new ExecutionGraph("serialized");
        graph.addNode(GraphNode.builder().nodeId("A").outputType(ArtifactType.GENERAL).build());
        Artifact<?> artifact = Artifact.of("art-A", ArtifactType.GENERAL, "A", Map.of("value", 42));
        DagCheckpoint checkpoint = new DagCheckpoint("run", 7L,java.util.UUID.randomUUID(), null,  "session", "original prompt", true, null,
                ExecutionGraphSnapshot.from(graph),
                Map.of("A", NodeStatus.SUCCEEDED), Map.of("A", artifact), java.time.Instant.now());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        DagCheckpoint restored = mapper.readValue(mapper.writeValueAsBytes(checkpoint), DagCheckpoint.class);

        assertThat(restored.userId()).isEqualTo(7L);
        assertThat(restored.prompt()).isEqualTo("original prompt");
        assertThat(restored.enableThinking()).isTrue();
        assertThat(restored.graph().restore().getNode("A").getOutputType()).isEqualTo(ArtifactType.GENERAL);
        assertThat(restored.artifacts().get("A").payload()).isEqualTo(Map.of("value", 42));
    }

    @Test
    void newRuntimeRestoresGraphAndFullArtifactsForOwner() throws Exception {
        InMemoryDagCheckpointStore store = new InMemoryDagCheckpointStore();
        ExecutionGraph graph = new ExecutionGraph("resume-owned");
        graph.addNode(GraphNode.builder().nodeId("A").outputType(ArtifactType.GENERAL).failurePolicy(FailurePolicy.FAIL_FAST).build());
        graph.addNode(GraphNode.builder().nodeId("B").outputType(ArtifactType.GENERAL).failurePolicy(FailurePolicy.FAIL_FAST).build());
        graph.addEdge("A", "B");
        AtomicInteger firstExecutions = new AtomicInteger();
        DagRuntime first = runtime(store, (node, artifacts, token) -> {
            firstExecutions.incrementAndGet();
            if ("B".equals(node.getNodeId())) throw new IllegalStateException("crash");
            return Artifact.of("art-A", ArtifactType.GENERAL, "A", Map.of("value", 42));
        });
        GraphRunRequest request = request(7L, "owned-run");

        assertThatThrownBy(() -> first.run(request, graph).completion().get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);

        AtomicInteger resumedExecutions = new AtomicInteger();
        DagRuntime second = runtime(store, (node, input, context) -> {
            resumedExecutions.incrementAndGet();
            assertThat(context.artifacts().get("A").payload()).isEqualTo(Map.of("value", 42));
            assertThat(context.request().prompt()).isEqualTo("prompt");
            assertThat(context.request().sessionKey()).isEqualTo("session");
            return Artifact.of("art-B", ArtifactType.GENERAL, "B", "done");
        });
        GraphRunHandle resumed = second.resume(7L, "owned-run", ignored -> {});
        var events = resumed.events().collectList().toFuture();
        GraphRunResult result = resumed.completion().get(2, TimeUnit.SECONDS);
        List<String> eventTypes = events.get(2, TimeUnit.SECONDS).stream().map(event -> event.getType()).toList();

        assertThat(resumedExecutions).hasValue(1);
        assertThat(result.artifacts()).containsKeys("A", "B");
        assertThat(eventTypes).contains("graph_initialized", "node_started", "node_completed", "run_completed");
        assertThat(store.load(8L, "owned-run")).isEmpty();
    }

    private DagRuntime runtime(DagCheckpointStore store, NodeExecutor executor) {
        return new DagRuntime(executor, ResourceManager.defaultManager(), store,
                new DefaultNodeQualityGate(), ReplanPolicy.never(), null);
    }

    private GraphRunRequest request(Long userId, String runId) {
        return new GraphRunRequest(runId, userId,java.util.UUID.randomUUID(), null,  "session", "prompt", false, null, ignored -> {}, RunMode.SYNC);
    }
}
