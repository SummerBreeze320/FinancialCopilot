package com.financial.copilot.agent.core.business.agents.a2a.protocol;

import java.time.Instant;

/** A2A Task SSE 流式推送事件 */
public record A2aTaskEvent(
        String type,
        String taskId,
        Object payload,
        Instant timestamp
) {
    public static A2aTaskEvent status(String taskId, A2aTaskStatus status) {
        return new A2aTaskEvent("status_update", taskId, status.name(), Instant.now());
    }

    public static A2aTaskEvent textDelta(String taskId, String text) {
        return new A2aTaskEvent("text_delta", taskId, text, Instant.now());
    }

    public static A2aTaskEvent artifact(String taskId, Object artifactPayload) {
        return new A2aTaskEvent("artifact_update", taskId, artifactPayload, Instant.now());
    }

    public static A2aTaskEvent error(String taskId, String errorMsg) {
        return new A2aTaskEvent("error", taskId, errorMsg, Instant.now());
    }
}
