package com.financial.copilot.agent.core.infra.agentscope;

import java.util.Objects;

/**
 * <h1>Agent 节点单次调用的强类型身份标识</h1>
 * 关联顶层工作流 Run、节点 ID 与当前重试轮次，杜绝跨节点和跨轮次上下文污染。
 */
public record AgentInvocationIdentity(
        String workflowRunId,
        String agentRunId,
        String nodeId,
        int attempt
) {
    public AgentInvocationIdentity {
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(agentRunId, "agentRunId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        if (workflowRunId.isBlank()) throw new IllegalArgumentException("workflowRunId must not be blank");
        if (agentRunId.isBlank()) throw new IllegalArgumentException("agentRunId must not be blank");
        if (nodeId.isBlank()) throw new IllegalArgumentException("nodeId must not be blank");
        if (attempt < 1) throw new IllegalArgumentException("attempt must be >= 1");
    }

    public static AgentInvocationIdentity of(String workflowRunId, String nodeId, int attempt) {
        String agentRunId = workflowRunId + ":" + nodeId + ":" + attempt;
        return new AgentInvocationIdentity(workflowRunId, agentRunId, nodeId, attempt);
    }
}
