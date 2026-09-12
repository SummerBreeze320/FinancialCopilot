package com.financial.copilot.agent.core.dag.model.patch;

/**
 * <h1>DAG 增量差分补丁操作类型枚举</h1>
 */
public enum PatchOp {
    ADD_NODE,
    REMOVE_NODE,
    ADD_EDGE,
    REMOVE_EDGE,
    UPDATE_NODE,
    SKIP_NODE,
    RETRY_NODE
}
