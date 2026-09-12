package com.financial.copilot.agent.core.dag.model;

import com.financial.copilot.agent.core.dag.model.patch.GraphOperation;
import com.financial.copilot.agent.core.dag.model.patch.GraphPatch;
import com.financial.copilot.agent.core.dag.model.patch.PatchOp;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
        Objects.requireNonNull(patch, "patch cannot be null");
        if (this.revision != patch.baseRevision()) {
            throw new ConcurrentModificationException(
                    "Graph revision mismatch! Current: " + this.revision + ", Patch base: " + patch.baseRevision()
            );
        }

        for (GraphOperation op : patch.operations()) {
            switch (op.op()) {
                case ADD_NODE -> {
                    if (op.node() != null) {
                        addNode(op.node());
                    }
                }
                case REMOVE_NODE -> {
                    if (op.nodeId() != null) {
                        removeNode(op.nodeId());
                    }
                }
                case ADD_EDGE -> {
                    if (op.from() != null && op.to() != null) {
                        addEdge(op.from(), op.to());
                    }
                }
                case REMOVE_EDGE -> {
                    if (op.from() != null && op.to() != null) {
                        removeEdge(op.from(), op.to());
                    }
                }
                case UPDATE_NODE -> {
                    if (op.nodeId() != null && op.params() != null) {
                        updateNodeParams(op.nodeId(), op.params());
                    }
                }
                case SKIP_NODE -> {
                    if (op.nodeId() != null) {
                        markNodeSkipped(op.nodeId());
                    }
                }
                case RETRY_NODE -> {
                    if (op.nodeId() != null) {
                        markNodeForRetry(op.nodeId());
                    }
                }
            }
        }

        if (hasCycle()) {
            throw new IllegalStateException("Applying patch created a circular dependency cycle!");
        }

        return ++this.revision;
    }

    public String getGraphId() {
        return graphId;
    }

    public int getRevision() {
        return revision;
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
