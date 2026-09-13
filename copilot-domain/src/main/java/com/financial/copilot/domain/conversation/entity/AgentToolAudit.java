package com.financial.copilot.domain.conversation.entity;
import java.util.*;
import java.time.Instant;
import com.financial.copilot.domain.conversation.model.ToolAuditStatus;
public record AgentToolAudit(Long id, UUID conversationId, Long assistantMessageId, Long userId, UUID runId, String nodeId, String agentName, String toolCallId, String toolName, ToolAuditStatus status, Map<String,Object> arguments, String resultSummary, String resultHash, List<String> artifactIds, Instant startedAt, Instant completedAt, Long durationMs, String errorCode, String errorMessage) {
 public AgentToolAudit { Objects.requireNonNull(conversationId); Objects.requireNonNull(assistantMessageId); Objects.requireNonNull(userId); Objects.requireNonNull(runId); Objects.requireNonNull(nodeId); Objects.requireNonNull(agentName); Objects.requireNonNull(toolCallId); Objects.requireNonNull(toolName); Objects.requireNonNull(status); Objects.requireNonNull(startedAt); arguments=Map.copyOf(arguments); artifactIds=List.copyOf(artifactIds); }
}
