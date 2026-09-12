package com.financial.copilot.agent.core.dag.model;

import com.financial.copilot.agent.core.dag.model.patch.GraphOperation;
import com.financial.copilot.agent.core.dag.model.patch.GraphPatch;
import com.financial.copilot.agent.core.dag.model.patch.PatchOp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionGraphTest {

    private GraphNode createSimpleNode(String nodeId, String name) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .name(name)
                .taskType("TEST")
                .timeout(Duration.ofSeconds(10))
                .failurePolicy(FailurePolicy.CONTINUE)
                .build();
    }

    @Test
    @DisplayName("构建基础拓扑图并验证入度、出度与根节点识别")
    void testBasicGraphTopology() {
        ExecutionGraph graph = new ExecutionGraph("test-graph-1");
        GraphNode nodeA = createSimpleNode("A", "Node A");
        GraphNode nodeB = createSimpleNode("B", "Node B");
        GraphNode nodeC = createSimpleNode("C", "Node C");

        graph.addNode(nodeA);
        graph.addNode(nodeB);
        graph.addNode(nodeC);

        graph.addEdge("A", "B");
        graph.addEdge("A", "C");

        assertThat(graph.getNodes()).hasSize(3);
        assertThat(graph.getRootNodeIds()).containsExactly("A");
        assertThat(graph.getDownstream("A")).containsExactlyInAnyOrder("B", "C");
        assertThat(graph.getUpstream("B")).containsExactly("A");
        assertThat(graph.getUpstream("C")).containsExactly("A");
        assertThat(graph.hasCycle()).isFalse();
    }

    @Test
    @DisplayName("检测直接循环依赖并回滚抛出异常")
    void testDirectCycleDetection() {
        ExecutionGraph graph = new ExecutionGraph("cycle-graph-1");
        GraphNode nodeA = createSimpleNode("A", "Node A");
        GraphNode nodeB = createSimpleNode("B", "Node B");

        graph.addNode(nodeA);
        graph.addNode(nodeB);
        graph.addEdge("A", "B");

        assertThatThrownBy(() -> graph.addEdge("B", "A"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("creates a cycle");

        // 验证边已回滚
        assertThat(graph.getDownstream("B")).doesNotContain("A");
        assertThat(graph.hasCycle()).isFalse();
    }

    @Test
    @DisplayName("检测间接深层循环依赖")
    void testIndirectCycleDetection() {
        ExecutionGraph graph = new ExecutionGraph("cycle-graph-2");
        graph.addNode(createSimpleNode("A", "A"));
        graph.addNode(createSimpleNode("B", "B"));
        graph.addNode(createSimpleNode("C", "C"));

        graph.addEdge("A", "B");
        graph.addEdge("B", "C");

        assertThatThrownBy(() -> graph.addEdge("C", "A"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("creates a cycle");

        assertThat(graph.hasCycle()).isFalse();
    }

    @Test
    @DisplayName("计算各节点的拓扑 Wave 层级 (Wave Index)")
    void testTopologicalWaveCalculation() {
        // A -> B -> D
        // A -> C -> D
        // E (独立根节点) -> D
        ExecutionGraph graph = new ExecutionGraph("wave-graph");
        graph.addNode(createSimpleNode("A", "A"));
        graph.addNode(createSimpleNode("B", "B"));
        graph.addNode(createSimpleNode("C", "C"));
        graph.addNode(createSimpleNode("D", "D"));
        graph.addNode(createSimpleNode("E", "E"));

        graph.addEdge("A", "B");
        graph.addEdge("A", "C");
        graph.addEdge("B", "D");
        graph.addEdge("C", "D");
        graph.addEdge("E", "D");

        assertThat(graph.calculateTopologicalWave("A")).isEqualTo(0);
        assertThat(graph.calculateTopologicalWave("E")).isEqualTo(0);
        assertThat(graph.calculateTopologicalWave("B")).isEqualTo(1);
        assertThat(graph.calculateTopologicalWave("C")).isEqualTo(1);
        assertThat(graph.calculateTopologicalWave("D")).isEqualTo(2);
    }

    @Test
    @DisplayName("移除节点时级联清理关联的边")
    void testRemoveNode() {
        ExecutionGraph graph = new ExecutionGraph("remove-node-graph");
        graph.addNode(createSimpleNode("A", "A"));
        graph.addNode(createSimpleNode("B", "B"));
        graph.addNode(createSimpleNode("C", "C"));

        graph.addEdge("A", "B");
        graph.addEdge("B", "C");

        graph.removeNode("B");

        assertThat(graph.getNodes()).doesNotContainKey("B");
        assertThat(graph.getDownstream("A")).doesNotContain("B");
        assertThat(graph.getUpstream("C")).doesNotContain("B");
    }

    @Test
    @DisplayName("通过 GraphPatch 原子应用增量差分补丁并校验版本递增")
    void testApplyPatchSuccess() {
        ExecutionGraph graph = new ExecutionGraph("patch-graph");
        graph.addNode(createSimpleNode("A", "A"));
        graph.addNode(createSimpleNode("B", "B"));
        graph.addEdge("A", "B");

        assertThat(graph.getRevision()).isEqualTo(0);

        GraphNode nodeC = createSimpleNode("C", "C");
        GraphPatch patch = GraphPatch.of(
                0,
                GraphOperation.addNode(nodeC),
                GraphOperation.addEdge("A", "C"),
                GraphOperation.updateNode("B", Map.of("key1", "val1")),
                GraphOperation.skipNode("B")
        );

        int newRevision = graph.applyPatch(patch);
        assertThat(newRevision).isEqualTo(1);
        assertThat(graph.getRevision()).isEqualTo(1);
        assertThat(graph.getNodes()).containsKey("C");
        assertThat(graph.getDownstream("A")).contains("C");
        assertThat(graph.getNode("B").getParams()).containsEntry("key1", "val1");
        assertThat(graph.getNode("B").isSkipped()).isTrue();
    }

    @Test
    @DisplayName("当 baseRevision 不匹配时拒绝应用 Patch 并抛出乐观锁异常")
    void testApplyPatchRevisionMismatch() {
        ExecutionGraph graph = new ExecutionGraph("patch-mismatch-graph");
        graph.addNode(createSimpleNode("A", "A"));

        // 尝试用 baseRevision=5 应用于 revision=0 的图
        GraphPatch stalePatch = GraphPatch.of(5, GraphOperation.addNode(createSimpleNode("B", "B")));

        assertThatThrownBy(() -> graph.applyPatch(stalePatch))
                .isInstanceOf(ConcurrentModificationException.class)
                .hasMessageContaining("Graph revision mismatch");
    }
}
