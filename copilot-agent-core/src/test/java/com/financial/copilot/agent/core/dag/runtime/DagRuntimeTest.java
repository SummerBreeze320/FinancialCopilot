package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactMetadata;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.artifact.EvidenceContract;
import com.financial.copilot.agent.core.dag.guard.DefaultNodeQualityGate;
import com.financial.copilot.agent.core.dag.guard.NodeQualityGate;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.FailurePolicy;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.model.NodeStatus;
import com.financial.copilot.agent.core.dag.runtime.checkpoint.DagCheckpoint;
import com.financial.copilot.agent.core.dag.runtime.checkpoint.InMemoryDagCheckpointStore;
import com.financial.copilot.agent.core.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.dag.runtime.resource.NodePriority;
import com.financial.copilot.agent.core.dag.runtime.resource.ResourceManager;
import com.financial.copilot.agent.core.dag.runtime.resource.ResourceRequirement;
import com.financial.copilot.agent.core.dag.runtime.resource.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DagRuntimeTest {

    private GraphNode createNode(String id, String name, FailurePolicy policy, NodePriority priority, ResourceRequirement req) {
        return GraphNode.builder()
                .nodeId(id)
                .name(name)
                .taskType("TEST")
                .timeout(Duration.ofSeconds(5))
                .failurePolicy(policy != null ? policy : FailurePolicy.CONTINUE)
                .priority(priority != null ? priority : NodePriority.NORMAL)
                .resourceRequirement(req != null ? req : ResourceRequirement.none())
                .build();
    }

    @Test
    @DisplayName("菱形依赖无物理屏障测试：A -> [B慢, C快] -> D(仅依赖C)，D应在C完成后立即启动，不等待B")
    void testDiamondDependencyZeroWavefrontBarrier() throws Exception {
        ExecutionGraph graph = new ExecutionGraph("diamond-graph");
        GraphNode nodeA = createNode("A", "Root", FailurePolicy.FAIL_FAST, NodePriority.NORMAL, null);
        GraphNode nodeB = createNode("B", "Slow Branch", FailurePolicy.CONTINUE, NodePriority.NORMAL, null);
        GraphNode nodeC = createNode("C", "Fast Branch", FailurePolicy.CONTINUE, NodePriority.NORMAL, null);
        GraphNode nodeD = createNode("D", "Consumer of C", FailurePolicy.CONTINUE, NodePriority.NORMAL, null);

        graph.addNode(nodeA);
        graph.addNode(nodeB);
        graph.addNode(nodeC);
        graph.addNode(nodeD);

        graph.addEdge("A", "B");
        graph.addEdge("A", "C");
        graph.addEdge("C", "D"); // D 仅依赖 C，与 B 并发独立！

        ArtifactStore store = new ArtifactStore();
        ResourceManager rm = ResourceManager.defaultManager();
        List<String> executionOrder = new CopyOnWriteArrayList<>();

        NodeExecutor executor = (node, artifactStore, token) -> {
            executionOrder.add(node.getNodeId() + "_START");
            if ("B".equals(node.getNodeId())) {
                Thread.sleep(300); // 模拟慢任务 300ms
            } else if ("C".equals(node.getNodeId())) {
                Thread.sleep(30);  // 模拟快任务 30ms
            }
            executionOrder.add(node.getNodeId() + "_DONE");
            return Artifact.of("art_" + node.getNodeId(), ArtifactType.GENERAL, node.getNodeId(), "ok", ArtifactMetadata.standard("TEST"));
        };

        DagRuntime runtime = new DagRuntime(executor, store, rm, new InMemoryDagCheckpointStore(), new DefaultNodeQualityGate());
        CompletableFuture<Void> future = runtime.executeGraph(graph, new CancellationToken("run-1"), event -> {});
        future.get(3, TimeUnit.SECONDS);

        // 验证 D_START 必定在 C_DONE 之后，且在 B_DONE 之前执行！
        int cDoneIndex = executionOrder.indexOf("C_DONE");
        int dStartIndex = executionOrder.indexOf("D_START");
        int bDoneIndex = executionOrder.indexOf("B_DONE");

        assertThat(cDoneIndex).isGreaterThanOrEqualTo(0);
        assertThat(dStartIndex).isGreaterThan(cDoneIndex);
        assertThat(dStartIndex).isLessThan(bDoneIndex);
    }

    @Test
    @DisplayName("优先级调度测试：当 LLM 资源配额为 1 时，HIGH 优先级节点比 NORMAL 节点优先执行")
    void testPrioritySchedulingUnderResourceConstraint() throws Exception {
        ExecutionGraph graph = new ExecutionGraph("priority-graph");
        GraphNode nodeRoot = createNode("ROOT", "Root", FailurePolicy.FAIL_FAST, NodePriority.NORMAL, null);
        // B 为 NORMAL, C 为 HIGH，两者都依赖 ROOT，且争抢 LLM:1 信号量
        GraphNode nodeB = createNode("B", "Normal Node", FailurePolicy.CONTINUE, NodePriority.NORMAL, ResourceRequirement.llm(1));
        GraphNode nodeC = createNode("C", "High Node", FailurePolicy.CONTINUE, NodePriority.HIGH, ResourceRequirement.llm(1));

        graph.addNode(nodeRoot);
        graph.addNode(nodeB);
        graph.addNode(nodeC);
        graph.addEdge("ROOT", "B");
        graph.addEdge("ROOT", "C");

        ArtifactStore store = new ArtifactStore();
        ResourceManager rm = new ResourceManager(Map.of(ResourceType.LLM, 1));
        List<String> startOrder = new CopyOnWriteArrayList<>();

        NodeExecutor executor = (node, artifactStore, token) -> {
            startOrder.add(node.getNodeId());
            Thread.sleep(50);
            return Artifact.of("art_" + node.getNodeId(), ArtifactType.GENERAL, node.getNodeId(), "ok", ArtifactMetadata.standard("TEST"));
        };

        DagRuntime runtime = new DagRuntime(executor, store, rm, new InMemoryDagCheckpointStore(), new DefaultNodeQualityGate());
        CompletableFuture<Void> future = runtime.executeGraph(graph, new CancellationToken("run-priority"), event -> {});
        future.get(3, TimeUnit.SECONDS);

        // 验证 C 比 B 先启动（HIGH 优先级抢占）
        int bIdx = startOrder.indexOf("B");
        int cIdx = startOrder.indexOf("C");
        assertThat(cIdx).isLessThan(bIdx);
    }

    @Test
    @DisplayName("树状结构化取消测试：触发 rootToken.cancel 级联中断正在运行的工作线程并释放资源")
    void testTreeStructuredCancellationPropagation() throws Exception {
        ExecutionGraph graph = new ExecutionGraph("cancel-graph");
        GraphNode nodeA = createNode("A", "Root", FailurePolicy.FAIL_FAST, NodePriority.NORMAL, ResourceRequirement.llm(1));
        GraphNode nodeB = createNode("B", "Child", FailurePolicy.FAIL_FAST, NodePriority.NORMAL, ResourceRequirement.llm(1));
        graph.addNode(nodeA);
        graph.addNode(nodeB);
        graph.addEdge("A", "B");

        ArtifactStore store = new ArtifactStore();
        ResourceManager rm = new ResourceManager(Map.of(ResourceType.LLM, 1));
        CancellationToken runToken = new CancellationToken("run-cancel");

        AtomicBoolean threadInterrupted = new AtomicBoolean(false);
        CountDownLatch nodeAStarted = new CountDownLatch(1);
        CountDownLatch nodeAInterrupted = new CountDownLatch(1);

        NodeExecutor executor = (node, artifactStore, token) -> {
            if ("A".equals(node.getNodeId())) {
                nodeAStarted.countDown();
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    threadInterrupted.set(true);
                    nodeAInterrupted.countDown();
                    throw e;
                }
            }
            return Artifact.of("art_" + node.getNodeId(), ArtifactType.GENERAL, node.getNodeId(), "ok", ArtifactMetadata.standard("TEST"));
        };

        DagRuntime runtime = new DagRuntime(executor, store, rm, new InMemoryDagCheckpointStore(), new DefaultNodeQualityGate());
        CompletableFuture<Void> future = runtime.executeGraph(graph, runToken, event -> {});

        // 等待 A 运行并持有信号量
        nodeAStarted.await(1, TimeUnit.SECONDS);
        // 主动触发全局取消
        runToken.cancel("User aborted request");

        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(CancellationException.class);

        // 等待虚拟线程完成中断处理
        boolean interrupted = nodeAInterrupted.await(2, TimeUnit.SECONDS);
        assertThat(interrupted).isTrue();
        // 验证虚拟线程收到中断信号
        assertThat(threadInterrupted.get()).isTrue();
        // 验证信号量已彻底归还
        assertThat(rm.getAvailablePermits(ResourceType.LLM)).isEqualTo(1);
    }

    @Test
    @DisplayName("节点级失败策略测试：CONTINUE 降级放行、OPTIONAL 跳过、FAIL_FAST 阻断")
    void testFailurePolicies() throws Exception {
        // 1. FAIL_FAST 抛异常中断全局
        ExecutionGraph failFastGraph = new ExecutionGraph("failfast-graph");
        failFastGraph.addNode(createNode("N1", "N1", FailurePolicy.FAIL_FAST, NodePriority.NORMAL, null));
        ArtifactStore store1 = new ArtifactStore();
        DagRuntime runtime1 = new DagRuntime(
                (node, s, t) -> { throw new RuntimeException("Crash"); },
                store1,
                ResourceManager.defaultManager(),
                new InMemoryDagCheckpointStore(),
                new DefaultNodeQualityGate()
        );
        assertThatThrownBy(() -> runtime1.executeGraph(failFastGraph, new CancellationToken("r1"), e -> {}).get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);

        // 2. CONTINUE 降级放行，产物标记 partial=true
        ExecutionGraph continueGraph = new ExecutionGraph("continue-graph");
        continueGraph.addNode(createNode("C1", "C1", FailurePolicy.CONTINUE, NodePriority.NORMAL, null));
        ArtifactStore store2 = new ArtifactStore();
        DagRuntime runtime2 = new DagRuntime(
                (node, s, t) -> { throw new RuntimeException("Minor Error"); },
                store2,
                ResourceManager.defaultManager(),
                new InMemoryDagCheckpointStore(),
                new DefaultNodeQualityGate()
        );
        runtime2.executeGraph(continueGraph, new CancellationToken("r2"), e -> {}).get(2, TimeUnit.SECONDS);
        Artifact<?> art = store2.get("C1");
        assertThat(art).isNotNull();
        assertThat(art.metadata().partial()).isTrue();

        // 3. OPTIONAL 失败静默标记为 SKIPPED，不阻断下游
        ExecutionGraph optGraph = new ExecutionGraph("optional-graph");
        optGraph.addNode(createNode("OPT", "OPT", FailurePolicy.OPTIONAL, NodePriority.NORMAL, null));
        optGraph.addNode(createNode("DOWN", "DOWN", FailurePolicy.CONTINUE, NodePriority.NORMAL, null));
        optGraph.addEdge("OPT", "DOWN");
        ArtifactStore store3 = new ArtifactStore();
        DagRuntime runtime3 = new DagRuntime(
                (node, s, t) -> {
                    if ("OPT".equals(node.getNodeId())) throw new RuntimeException("Optional Branch Failed");
                    return Artifact.of("art_DOWN", ArtifactType.GENERAL, "DOWN", "ok", ArtifactMetadata.standard("TEST"));
                },
                store3,
                ResourceManager.defaultManager(),
                new InMemoryDagCheckpointStore(),
                new DefaultNodeQualityGate()
        );
        runtime3.executeGraph(optGraph, new CancellationToken("r3"), e -> {}).get(2, TimeUnit.SECONDS);
        assertThat(store3.get("DOWN")).isNotNull();
    }

    @Test
    @DisplayName("断点续跑测试：A -> B -> C -> D，模拟崩溃后调用 resume，已成功节点绝不重复执行")
    void testCheckpointAndResume() throws Exception {
        ExecutionGraph graph = new ExecutionGraph("resume-graph");
        graph.addNode(createNode("A", "A", FailurePolicy.FAIL_FAST, NodePriority.NORMAL, null));
        graph.addNode(createNode("B", "B", FailurePolicy.FAIL_FAST, NodePriority.NORMAL, null));
        graph.addNode(createNode("C", "C", FailurePolicy.FAIL_FAST, NodePriority.NORMAL, null));
        graph.addEdge("A", "B");
        graph.addEdge("B", "C");

        InMemoryDagCheckpointStore checkpointStore = new InMemoryDagCheckpointStore();
        ArtifactStore store = new ArtifactStore();
        ResourceManager rm = ResourceManager.defaultManager();
        Set<String> executedNodes = ConcurrentHashMap.newKeySet();

        AtomicBoolean failAtC = new AtomicBoolean(true);

        NodeExecutor executor = (node, artifactStore, token) -> {
            executedNodes.add(node.getNodeId());
            if ("C".equals(node.getNodeId()) && failAtC.get()) {
                throw new RuntimeException("Crash at C");
            }
            return Artifact.of("art_" + node.getNodeId(), ArtifactType.GENERAL, node.getNodeId(), "data_" + node.getNodeId(), ArtifactMetadata.standard("TEST"));
        };

        DagRuntime runtime = new DagRuntime(executor, store, rm, checkpointStore, new DefaultNodeQualityGate());
        String runId = "resume-run-1";

        // 第一轮执行：在 C 崩溃
        assertThatThrownBy(() -> runtime.executeGraph(runId, graph, new CancellationToken("t1"), e -> {}).get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);

        assertThat(executedNodes).contains("A", "B", "C");
        assertThat(checkpointStore.loadCheckpoint(runId)).isPresent();
        DagCheckpoint cp = checkpointStore.loadCheckpoint(runId).get();
        assertThat(cp.isNodeCompleted("A")).isTrue();
        assertThat(cp.isNodeCompleted("B")).isTrue();
        assertThat(cp.isNodeCompleted("C")).isFalse();

        // 修复/重置状态，准备断点续跑
        executedNodes.clear();
        failAtC.set(false); // 恢复正常

        // 执行断点续跑 resume
        runtime.resume(runId, graph, new CancellationToken("t2"), e -> {}).get(2, TimeUnit.SECONDS);

        // 验证 A 和 B 没有再次执行，只有 C 被执行了！
        assertThat(executedNodes).doesNotContain("A", "B");
        assertThat(executedNodes).contains("C");
        assertThat(store.get("C")).isNotNull();
    }
}
