package com.financial.copilot.domain.platform.execution.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * <h1>集群化运行状态实体 (Research Run State)</h1>
 * <p>
 * 对应多智能体体系下单次运行的执行状态，具备乐观锁版本控制与执行成果/证据链快照。
 * </p>
 *
 * @param runId 运行唯一标识 UUID
 * @param userId 用户 ID
 * @param conversationId 对话会话 ID
 * @param status 运行状态（PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED）
 * @param executionSnapshot 执行状态与事实证据链快照 JSON 字符串
 * @param version 乐观锁版本号
 * @param createdAt 创建时间戳
 * @param updatedAt 更新时间戳
 * @author FinancialCopilot
 */
public record ResearchRunState(
        UUID runId,
        Long userId,
        UUID conversationId,
        String status,
        String executionSnapshot,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
    public ResearchRunState {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(conversationId, "conversationId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        if (version == null) {
            version = 0L;
        }
        if (executionSnapshot == null) {
            executionSnapshot = "{}";
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    /**
     * 向前兼容旧图快照访问器
     *
     * @return 执行快照 JSON 字符串
     */
    public String graphSnapshot() {
        return executionSnapshot;
    }
}
