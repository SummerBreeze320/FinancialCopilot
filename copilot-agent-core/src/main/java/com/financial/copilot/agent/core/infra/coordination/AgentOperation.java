package com.financial.copilot.agent.core.infra.coordination;

import com.financial.copilot.agent.core.infra.agent.AgentRole;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * <h1>不可变智能体操作账本记录 (AgentOperation)</h1>
 * <p>
 * 用于所有 Supervisor、Subagent 与 Tool 调用的操作追踪、并发抑重与幂等审计。
 * </p>
 *
 * @param operationId 操作全局唯一 ID
 * @param idempotencyKey 服务端生成的强幂等键 (workflowRunId:role:toolName:canonicalHash)
 * @param workflowRunId 工作流执行 ID
 * @param agentRunId 单次 Agent 执行 ID
 * @param role 执行该操作的 Agent 角色
 * @param operationType 操作类型或工具方法名
 * @param status 操作当前状态
 * @param expectedRevision 执行时预期的 Evidence 快照版本号
 * @param resultReference 紧凑结果引用或脱敏成功摘要
 * @param startedAt 操作发起时间戳
 * @param completedAt 操作完成时间戳
 */
public record AgentOperation(
        String operationId,
        String idempotencyKey,
        String workflowRunId,
        String agentRunId,
        AgentRole role,
        String operationType,
        OperationStatus status,
        long expectedRevision,
        String resultReference,
        Instant startedAt,
        Instant completedAt
) {
    public AgentOperation {
        operationId = (operationId == null || operationId.isBlank()) ? UUID.randomUUID().toString() : operationId;
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId cannot be null");
        Objects.requireNonNull(agentRunId, "agentRunId cannot be null");
        Objects.requireNonNull(role, "role cannot be null");
        Objects.requireNonNull(operationType, "operationType cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        startedAt = startedAt == null ? Instant.now() : startedAt;
    }

    public static AgentOperation requested(
            String idempotencyKey,
            String workflowRunId,
            String agentRunId,
            AgentRole role,
            String operationType,
            long expectedRevision
    ) {
        return new AgentOperation(
                UUID.randomUUID().toString(),
                idempotencyKey,
                workflowRunId,
                agentRunId,
                role,
                operationType,
                OperationStatus.REQUESTED,
                expectedRevision,
                null,
                Instant.now(),
                null
        );
    }

    public AgentOperation toRunning() {
        return new AgentOperation(
                operationId,
                idempotencyKey,
                workflowRunId,
                agentRunId,
                role,
                operationType,
                OperationStatus.RUNNING,
                expectedRevision,
                resultReference,
                startedAt,
                null
        );
    }

    public AgentOperation toSucceeded(String resultRef) {
        return new AgentOperation(
                operationId,
                idempotencyKey,
                workflowRunId,
                agentRunId,
                role,
                operationType,
                OperationStatus.SUCCEEDED,
                expectedRevision,
                resultRef,
                startedAt,
                Instant.now()
        );
    }

    public AgentOperation toFailed(String errorMsg) {
        return new AgentOperation(
                operationId,
                idempotencyKey,
                workflowRunId,
                agentRunId,
                role,
                operationType,
                OperationStatus.FAILED,
                expectedRevision,
                errorMsg,
                startedAt,
                Instant.now()
        );
    }
}
