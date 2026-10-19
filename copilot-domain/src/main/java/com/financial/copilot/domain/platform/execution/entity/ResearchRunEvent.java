package com.financial.copilot.domain.platform.execution.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * <h1>运行流事件持久化实体 (Research Run Event)</h1>
 * <p>
 * 记录单次运行发出的有序可重放流事件，带有严格单调递增的 sequence_no，支持 SSE Last-Event-ID 重连断点续传。
 * </p>
 *
 * @param runId 运行唯一标识 UUID
 * @param userId 用户 ID（用于安全归属鉴权）
 * @param sequenceNo 事件在当前 Run 下的单调递增序号（从 1 开始）
 * @param eventType 事件类型（如 run_started, node_started, token_delta, run_completed）
 * @param payload 事件载荷 JSON 字符串
 * @param createdAt 事件产生时间
 * @author FinancialCopilot
 */
public record ResearchRunEvent(
        UUID runId,
        Long userId,
        Long sequenceNo,
        String eventType,
        String payload,
        Instant createdAt
) {
    public ResearchRunEvent {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(sequenceNo, "sequenceNo cannot be null");
        Objects.requireNonNull(eventType, "eventType cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
