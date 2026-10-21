package com.financial.copilot.agent.core.infra.tool;

import com.financial.copilot.domain.platform.conversation.model.ToolAuditStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <h1>工具调用审计事件 (ToolAuditEvent)</h1>
 * 严格仅记录脱敏审计元数据，严禁记录原始模型消息或完整大载荷。
 */
public record ToolAuditEvent(
        UUID conversationId,
        Long assistantMessageId,
        Long userId,
        UUID runId,
        String nodeId,
        String agentName,
        String toolCallId,
        String toolName,
        ToolAuditStatus status,
        Map<String, Object> arguments,
        String resultSummary,
        String resultHash,
        List<String> artifactIds,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        String errorCode,
        String errorMessage
) {}
