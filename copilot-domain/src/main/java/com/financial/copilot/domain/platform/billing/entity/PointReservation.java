package com.financial.copilot.domain.platform.billing.entity;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * <h1>算力预占与结算实体 (Point Reservation Entity)</h1>
 * <p>
 * 对应单个投研运行的算力预占、结算与释放生命周期。
 * 具备基于 workflowRunId 的唯一幂等防重保证。
 * </p>
 *
 * @param id 预占自增主键 ID (可选，持久化后生成)
 * @param userId 用户唯一标识 ID
 * @param workflowRunId 投研运行唯一标识 (唯一幂等约束)
 * @param estimatedPoints 任务启动前预估冻结积分
 * @param actualPoints 任务完成时实际结算扣减积分
 * @param status 预占状态 (ACTIVE, SETTLED, RELEASED)
 * @param createdAt 创建时间戳
 * @param updatedAt 更新时间戳
 * @author FinancialCopilot
 */
public record PointReservation(
        Long id,
        Long userId,
        String workflowRunId,
        Long estimatedPoints,
        Long actualPoints,
        ReservationStatus status,
        Instant createdAt,
        Instant updatedAt
) implements Serializable {

    public PointReservation {
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        if (estimatedPoints == null) {
            estimatedPoints = 0L;
        }
        if (actualPoints == null) {
            actualPoints = 0L;
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }
}
