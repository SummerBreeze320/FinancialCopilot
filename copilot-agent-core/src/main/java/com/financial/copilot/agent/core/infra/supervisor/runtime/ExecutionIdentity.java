package com.financial.copilot.agent.core.infra.supervisor.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * <h1>不可变执行身份标识</h1>
 * 严格包含租户、用户以及会话隔离边界，由服务端鉴权层统一颁发与验证，禁止模型工具修改。
 *
 * @param tenantId 租户 ID
 * @param userId 用户 ID
 * @param conversationId 会话 UUID
 */
public record ExecutionIdentity(
        Long tenantId,
        Long userId,
        UUID conversationId
) {
    public ExecutionIdentity {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(conversationId, "conversationId cannot be null");
    }
}
