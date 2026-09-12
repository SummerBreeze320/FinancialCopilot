package com.financial.copilot.agent.core.dag.model.patch;

import com.financial.copilot.agent.core.dag.model.GraphNode;

import java.util.Map;

/**
 * <h1>DAG 拓扑差分原子操作指令</h1>
 */
public record GraphOperation(
    PatchOp op,
    GraphNode node,
    String nodeId,
    String from,
    String to,
    Map<String, Object> params
) {
    public static GraphOperation addNode(GraphNode node) {
        return new GraphOperation(PatchOp.ADD_NODE, node, node != null ? node.getNodeId() : null, null, null, null);
    }

    public static GraphOperation removeNode(String nodeId) {
        return new GraphOperation(PatchOp.REMOVE_NODE, null, nodeId, null, null, null);
    }

    public static GraphOperation addEdge(String from, String to) {
        return new GraphOperation(PatchOp.ADD_EDGE, null, null, from, to, null);
    }

    public static GraphOperation removeEdge(String from, String to) {
        return new GraphOperation(PatchOp.REMOVE_EDGE, null, null, from, to, null);
    }

    public static GraphOperation skipNode(String nodeId) {
        return new GraphOperation(PatchOp.SKIP_NODE, null, nodeId, null, null, null);
    }

    public static GraphOperation retryNode(String nodeId) {
        return new GraphOperation(PatchOp.RETRY_NODE, null, nodeId, null, null, null);
    }

    public static GraphOperation updateNode(String nodeId, Map<String, Object> params) {
        return new GraphOperation(PatchOp.UPDATE_NODE, null, nodeId, null, null, params);
    }
}
