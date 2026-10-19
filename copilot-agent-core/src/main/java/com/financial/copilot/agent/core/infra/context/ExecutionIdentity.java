package com.financial.copilot.agent.core.infra.context;

import java.util.Objects;
import java.util.UUID;

/**
 * 不可变执行身份，在服务端派生并严格隔离租户、用户、会话与工作流运行标识。
 */
public record ExecutionIdentity(
        String tenantId,
        Long userId,
        UUID conversationId,
        String workflowRunId,
        String sessionKey
) {
    public ExecutionIdentity {
        tenantId = requireText(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(conversationId, "conversationId cannot be null");
        workflowRunId = requireText(workflowRunId, "workflowRunId");
        sessionKey = requireText(sessionKey, "sessionKey");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
