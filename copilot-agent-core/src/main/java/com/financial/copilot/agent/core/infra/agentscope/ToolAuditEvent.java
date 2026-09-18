package com.financial.copilot.agent.core.infra.agentscope;
import com.financial.copilot.domain.platform.conversation.model.ToolAuditStatus;
import java.time.Instant;
import java.util.*;
/** Sanitized metadata only: never a model message or raw tool result. */
public record ToolAuditEvent(UUID conversationId, Long assistantMessageId, Long userId, UUID runId,
 String nodeId, String agentName, String toolCallId, String toolName, ToolAuditStatus status,
 Map<String,Object> arguments, String resultSummary, String resultHash, List<String> artifactIds,
 Instant startedAt, Instant completedAt, Long durationMs, String errorCode, String errorMessage) {}
