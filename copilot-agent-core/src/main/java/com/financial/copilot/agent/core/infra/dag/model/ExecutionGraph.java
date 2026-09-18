package com.financial.copilot.agent.core.infra.dag.model;

import com.financial.copilot.agent.core.infra.dag.model.patch.GraphOperation;
import com.financial.copilot.agent.core.infra.dag.model.patch.GraphPatch;
import com.financial.copilot.agent.core.infra.dag.model.patch.PatchOp;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.function.Predicate;

/**
 * <h1>DAG 拓扑执行图模型</h1>
 * 维护双向邻接表，支持严格有向无环图循环检测、Wave 拓扑计算及运行时原子差分补丁 (GraphPatch)。
 */
public class ExecutionGraph {

    private final String graphId;
    private volatile int revision = 0;
    private final Map<String, GraphNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> upstream = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> downstream = new ConcurrentHashMap<>();

    public ExecutionGraph(String graphId) {
        this.graphId = Objects.requireNonNull(graphId, "graphId cannot be null");
    }

    public synchronized void addNode(GraphNode node) {
        Objects.requireNonNull(node, "node cannot be null");
        nodes.put(node.getNodeId(), node);
        upstream.putIfAbsent(node.getNodeId(), ConcurrentHashMap.newKeySet());
        downstream.putIfAbsent(node.getNodeId(), ConcurrentHashMap.newKeySet());
    }

    public synchronized void addEdge(String fromNodeId, String toNodeId) {
        if (!nodes.containsKey(fromNodeId) || !nodes.containsKey(toNodeId)) {
            throw new IllegalArgumentException("Nodes must exist in graph before adding edge: " + fromNodeId + " -> " + toNodeId);
        }
        downstream.get(fromNodeId).add(toNodeId);
        upstream.get(toNodeId).add(fromNodeId);

        if (hasCycle()) {
            // 回滚并抛出异常
            downstream.get(fromNodeId).remove(toNodeId);
            upstream.get(toNodeId).remove(fromNodeId);
            throw new IllegalStateException("Adding edge " + fromNodeId + " -> " + toNodeId + " creates a cycle!");
        }
    }

    public synchronized void removeEdge(String fromNodeId, String toNodeId) {
        Set<String> downs = downstream.get(fromNodeId);
        if (downs != null) {
            downs.remove(toNodeId);
        }
        Set<String> ups = upstream.get(toNodeId);
        if (ups != null) {
            ups.remove(fromNodeId);
        }
    }

    public synchronized void removeNode(String nodeId) {
        nodes.remove(nodeId);
        Set<String> parents = upstream.remove(nodeId);
        if (parents != null) {
            parents.forEach(p -> {
                Set<String> children = downstream.get(p);
                if (children != null) {
                    children.remove(nodeId);
                }
            });
        }
        Set<String> children = downstream.remove(nodeId);
        if (children != null) {
            children.forEach(c -> {
                Set<String> pars = upstream.get(c);
                if (pars != null) {
                    pars.remove(nodeId);
                }
            });
        }
    }

    public synchronized void updateNodeParams(String nodeId, Map<String, Object> params) {
        GraphNode node = nodes.get(nodeId);
        if (node != null && params != null) {
            node.updateParams(params);
        }
    }

    public synchronized void markNodeSkipped(String nodeId) {
        GraphNode node = nodes.get(nodeId);
        if (node != null) {
            node.markSkipped();
        }
    }

    public synchronized void markNodeForRetry(String nodeId) {
        GraphNode node = nodes.get(nodeId);
        if (node != null) {
            node.incrementRetryCount();
        }
    }

    /**
     * 基于 Kahn 算法入度消除检测图中是否存在环
     */
    public boolean hasCycle() {
        Map<String, Integer> inDegree = new HashMap<>();
        nodes.keySet().forEach(id -> inDegree.put(id, upstream.getOrDefault(id, Set.of()).size()));

        Queue<String> queue = new ArrayDeque<>();
        inDegree.forEach((id, deg) -> {
            if (deg == 0) {
                queue.offer(id);
            }
        });

        int visited = 0;
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            visited++;
            for (String next : downstream.getOrDefault(curr, Set.of())) {
                if (!inDegree.containsKey(next)) {
                    continue;
                }
                int newDeg = inDegree.compute(next, (k, d) -> d == null ? 0 : d - 1);
                if (newDeg == 0) {
                    queue.offer(next);
                }
            }
        }
        return visited != nodes.size();
    }

    /**
     * 获取所有入度为 0 的根节点 ID 集合
     */
    public Set<String> getRootNodeIds() {
        return nodes.keySet().stream()
                .filter(id -> upstream.getOrDefault(id, Set.of()).isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 计算节点的拓扑层级 (Wave Index)，作为 UI 布局/解释元数据，运行时无物理屏障
     */
    public int calculateTopologicalWave(String nodeId) {
        Set<String> parents = upstream.getOrDefault(nodeId, Set.of());
        if (parents.isEmpty()) {
            return 0;
        }
        int maxParentWave = 0;
        for (String parentId : parents) {
            maxParentWave = Math.max(maxParentWave, calculateTopologicalWave(parentId));
        }
        return maxParentWave + 1;
    }

    /**
     * 原子应用增量差分补丁 (GraphPatch)
     */
    public synchronized int applyPatch(GraphPatch patch) {
        return applyPatch(patch, id -> true);
    }

    /** Applies a patch using copy-validate-swap so a failed operation cannot partly mutate the graph. */
    public synchronized int applyPatch(GraphPatch patch, Predicate<String> mutableNode) {
        Objects.requireNonNull(patch, "patch cannot be null");
        Objects.requireNonNull(mutableNode, "mutableNode cannot be null");
        if (this.revision != patch.baseRevision()) {
            throw new ConcurrentModificationException(
                    "Graph revision mismatch! Current: " + this.revision + ", Patch base: " + patch.baseRevision()
            );
        }

        Map<String, GraphNode> nextNodes = new HashMap<>();
        nodes.forEach((id, node) -> nextNodes.put(id, copyNode(node)));
        Map<String, Set<String>> nextUpstream = copyAdjacency(upstream);
        Map<String, Set<String>> nextDownstream = copyAdjacency(downstream);

        for (GraphOperation op : patch.operations()) {
            Objects.requireNonNull(op, "patch operation cannot be null");
            switch (op.op()) {
                case ADD_NODE -> {
                    if (op.node() == null) throw new IllegalArgumentException("ADD_NODE requires node");
                    String id = op.node().getNodeId();
                    if (nextNodes.containsKey(id)) throw new IllegalStateException("Duplicate node: " + id);
                    nextNodes.put(id, copyNode(op.node()));
                    nextUpstream.put(id, new HashSet<>());
                    nextDownstream.put(id, new HashSet<>());
                }
                case REMOVE_NODE -> {
                    requireMutable(op.nodeId(), mutableNode);
                    requireNode(nextNodes, op.nodeId());
                    removeNode(nextNodes, nextUpstream, nextDownstream, op.nodeId());
                }
                case ADD_EDGE -> {
                    requireMutable(op.from(), mutableNode);
                    requireMutable(op.to(), mutableNode);
                    requireNode(nextNodes, op.from());
                    requireNode(nextNodes, op.to());
                    nextDownstream.get(op.from()).add(op.to());
                    nextUpstream.get(op.to()).add(op.from());
                }
                case REMOVE_EDGE -> {
                    requireMutable(op.from(), mutableNode);
                    requireMutable(op.to(), mutableNode);
                    requireNode(nextNodes, op.from());
                    requireNode(nextNodes, op.to());
                    nextDownstream.get(op.from()).remove(op.to());
                    nextUpstream.get(op.to()).remove(op.from());
                }
                case UPDATE_NODE -> {
                    requireMutable(op.nodeId(), mutableNode);
                    requireNode(nextNodes, op.nodeId());
                    nextNodes.get(op.nodeId()).updateParams(op.params());
                }
                case SKIP_NODE -> {
                    requireMutable(op.nodeId(), mutableNode);
                    requireNode(nextNodes, op.nodeId());
                    nextNodes.get(op.nodeId()).markSkipped();
                }
                case RETRY_NODE -> {
                    requireMutable(op.nodeId(), mutableNode);
                    requireNode(nextNodes, op.nodeId());
                    nextNodes.get(op.nodeId()).incrementRetryCount();
                }
            }
        }

        validateBindings(nextNodes);
        if (hasCycle(nextNodes, nextUpstream, nextDownstream)) {
            throw new IllegalStateException("Applying patch created a circular dependency cycle!");
        }

        nodes.clear();
        nodes.putAll(nextNodes);
        replaceAdjacency(upstream, nextUpstream);
        replaceAdjacency(downstream, nextDownstream);
        return ++this.revision;
    }

    public synchronized ExecutionGraph copy() {
        ExecutionGraph copy = new ExecutionGraph(graphId);
        nodes.forEach((id, node) -> copy.nodes.put(id, copyNode(node)));
        replaceAdjacency(copy.upstream, copyAdjacency(upstream));
        replaceAdjacency(copy.downstream, copyAdjacency(downstream));
        copy.revision = revision;
        return copy;
    }

    private static GraphNode copyNode(GraphNode node) {
        GraphNode copy = GraphNode.builder()
                .nodeId(node.getNodeId()).taskType(node.getTaskType()).name(node.getName())
                .requiredInputs(node.getRequiredInputs()).inputBindings(node.getInputBindings())
                .outputType(node.getOutputType()).params(node.getParams()).timeout(node.getTimeout())
                .failurePolicy(node.getFailurePolicy()).maxRetries(node.getMaxRetries())
                .fallbackProvider(node.getFallbackProvider()).resourceRequirement(node.getResourceRequirement())
                .resourceRequirements(node.getResourceRequirements()).priority(node.getPriority()).build();
        if (node.isSkipped()) copy.markSkipped();
        for (int i = 0; i < node.getRetryCount(); i++) copy.incrementRetryCount();
        return copy;
    }

    private static Map<String, Set<String>> copyAdjacency(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new HashMap<>();
        source.forEach((id, edges) -> copy.put(id, new HashSet<>(edges)));
        return copy;
    }

    private static void replaceAdjacency(Map<String, Set<String>> target, Map<String, Set<String>> source) {
        target.clear();
        source.forEach((id, edges) -> {
            Set<String> concurrent = ConcurrentHashMap.newKeySet();
            concurrent.addAll(edges);
            target.put(id, concurrent);
        });
    }

    private static void requireMutable(String nodeId, Predicate<String> mutableNode) {
        if (nodeId == null || !mutableNode.test(nodeId)) {
            throw new IllegalStateException("Node is not mutable: " + nodeId);
        }
    }

    private static void requireNode(Map<String, GraphNode> graphNodes, String nodeId) {
        if (nodeId == null || !graphNodes.containsKey(nodeId)) {
            throw new IllegalStateException("Unknown node: " + nodeId);
        }
    }

    private static void removeNode(Map<String, GraphNode> graphNodes,
                                   Map<String, Set<String>> graphUpstream,
                                   Map<String, Set<String>> graphDownstream,
                                   String nodeId) {
        graphNodes.remove(nodeId);
        graphUpstream.remove(nodeId);
        graphDownstream.remove(nodeId);
        graphUpstream.values().forEach(edges -> edges.remove(nodeId));
        graphDownstream.values().forEach(edges -> edges.remove(nodeId));
    }

    private static void validateBindings(Map<String, GraphNode> graphNodes) {
        for (GraphNode node : graphNodes.values()) {
            Set<String> names = new HashSet<>();
            for (InputBinding binding : node.getInputBindings()) {
                if (!names.add(binding.name())) throw new IllegalStateException("Duplicate input binding: " + binding.name());
                GraphNode producer = graphNodes.get(binding.producerNodeId());
                if (producer == null) throw new IllegalStateException("Unknown binding producer: " + binding.producerNodeId());
                if (producer.getOutputType() != binding.expectedType()) {
                    throw new IllegalStateException("Binding type does not match producer " + binding.producerNodeId());
                }
            }
        }
    }

    private static boolean hasCycle(Map<String, GraphNode> graphNodes,
                                    Map<String, Set<String>> graphUpstream,
                                    Map<String, Set<String>> graphDownstream) {
        Map<String, Integer> inDegree = new HashMap<>();
        graphNodes.keySet().forEach(id -> inDegree.put(id, graphUpstream.getOrDefault(id, Set.of()).size()));
        Queue<String> queue = new ArrayDeque<>();
        inDegree.forEach((id, degree) -> { if (degree == 0) queue.offer(id); });
        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            visited++;
            for (String next : graphDownstream.getOrDefault(current, Set.of())) {
                int degree = inDegree.computeIfPresent(next, (id, old) -> old - 1);
                if (degree == 0) queue.offer(next);
            }
        }
        return visited != graphNodes.size();
    }

    public String getGraphId() {
        return graphId;
    }

    public int getRevision() {
        return revision;
    }

    synchronized void restoreRevision(int revision) {
        if (revision < 0) throw new IllegalArgumentException("revision cannot be negative");
        this.revision = revision;
    }

    public Map<String, GraphNode> getNodes() {
        return Collections.unmodifiableMap(nodes);
    }

    public GraphNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public Set<String> getUpstream(String nodeId) {
        return Collections.unmodifiableSet(upstream.getOrDefault(nodeId, Set.of()));
    }

    public Set<String> getDownstream(String nodeId) {
        return Collections.unmodifiableSet(downstream.getOrDefault(nodeId, Set.of()));
    }
}
