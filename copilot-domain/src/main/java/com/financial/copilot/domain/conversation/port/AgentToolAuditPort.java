package com.financial.copilot.domain.conversation.port;

import com.financial.copilot.domain.conversation.entity.*;
import com.financial.copilot.domain.conversation.model.*;

import java.util.*;
import java.time.Instant;

public interface AgentToolAuditPort {
    void start(AgentToolAudit audit);

    void complete(Long userId, UUID runId, String toolCallId, ToolAuditStatus status, String summary, String hash, List<String> artifactIds, String errorCode, String errorMessage, Instant at, long durationMs);

    void cancelOpenForRun(Long userId, UUID runId, ToolAuditStatus status, String errorCode, String errorMessage, Instant at);

    CursorPage<AgentToolAudit> listByRun(Long userId, UUID runId, String cursor, int limit);
}
