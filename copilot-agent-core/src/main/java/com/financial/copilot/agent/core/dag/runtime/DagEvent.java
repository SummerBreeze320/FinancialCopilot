package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.dag.model.NodeStatus;

/**
 * <h1>DAG 运行时事件契约</h1>
 *
 * @param nodeId  触发事件的节点 ID
 * @param status  当前生命周期状态
 * @param message 描述说明
 * @param payload 关联产物或结构化数据
 */
public record DagEvent(
    String nodeId,
    NodeStatus status,
    String message,
    Object payload
) {
    public DagEvent(String nodeId, NodeStatus status, String message) {
        this(nodeId, status, message, null);
    }
}
