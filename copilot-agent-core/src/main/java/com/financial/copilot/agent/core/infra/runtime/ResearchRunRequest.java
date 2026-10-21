package com.financial.copilot.agent.core.infra.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * <h1>投研运行请求入参契约</h1>
 *
 * @param workflowRunId 服务端预分配的运行全局 ID
 * @param tenantId 租户 ID
 * @param userId 用户 ID
 * @param conversationId 会话 UUID
 * @param assistantMessageId 关联的助手回复消息 ID
 * @param prompt 用户提问文本
 * @param enableThinking 是否启用深度思考模型能力
 * @param mode 执行模式（同步阻塞或 SSE 流式响应）
 */
public record ResearchRunRequest(
        String workflowRunId,
        Long tenantId,
        Long userId,
        UUID conversationId,
        Long assistantMessageId,
        String prompt,
        boolean enableThinking,
        RunMode mode
) {
    public ResearchRunRequest {
        workflowRunId = requireText(workflowRunId, "workflowRunId");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(conversationId, "conversationId cannot be null");
        prompt = requireText(prompt, "prompt");
        mode = mode == null ? RunMode.STREAM : mode;
    }

    public ExecutionIdentity identity() {
        return new ExecutionIdentity(tenantId, userId, conversationId);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
