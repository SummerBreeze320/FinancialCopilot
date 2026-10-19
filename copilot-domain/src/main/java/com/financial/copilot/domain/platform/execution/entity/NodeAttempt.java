package com.financial.copilot.domain.platform.execution.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * <h1>节点执行尝试与分布式租约实体 (Node Attempt)</h1>
 * <p>
 * 对应多实例集群下的节点级别执行尝试与工作节点租约（Lease），支持故障转移与幂等重试。
 * 复合主键为 (runId, nodeId, attempt)。
 * </p>
 *
 * @param runId 运行唯一标识 UUID
 * @param nodeId 节点唯一标识
 * @param attempt 尝试序号（从 1 开始递增）
 * @param status 尝试状态（READY, RUNNING, SUCCEEDED, FAILED, CANCELLED, EXPIRED）
 * @param workerId 持有租约的工作节点标识（如 worker-host-port-uuid）
 * @param leaseUntil 租约有效期截止时间
 * @param artifact 节点产物 JSON 字符串（成功或产物暂存时）
 * @author FinancialCopilot
 */
public record NodeAttempt(
        UUID runId,
        String nodeId,
        Integer attempt,
        String status,
        String workerId,
        Instant leaseUntil,
        String artifact
) {
    public NodeAttempt {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(nodeId, "nodeId cannot be null");
        Objects.requireNonNull(attempt, "attempt cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
    }
}
