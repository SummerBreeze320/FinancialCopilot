package com.financial.copilot.agent.core.business.agents.a2a.manager;

import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskEvent;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskStatus;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A2A 长时任务会话上下文 */
public class A2aTaskSession {
    private final String taskId;
    private final String agentName;
    private final Map<String, Object> params;
    private final Long userId;
    private final Instant createdAt;
    private volatile Instant updatedAt;
    private volatile A2aTaskStatus status;
    private final CancellationToken cancellationToken;
    private final Sinks.Many<A2aTaskEvent> sink;
    private volatile Object artifactResult;

    public A2aTaskSession(String taskId, String agentName, Map<String, Object> params, Long userId) {
        this.taskId = taskId;
        this.agentName = agentName;
        this.params = params != null ? new ConcurrentHashMap<>(params) : new ConcurrentHashMap<>();
        this.userId = userId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.status = A2aTaskStatus.SUBMITTED;
        this.cancellationToken = new CancellationToken(taskId);
        this.sink = Sinks.many().replay().limit(100);
    }

    public void emitEvent(A2aTaskEvent event) {
        this.updatedAt = Instant.now();
        this.sink.tryEmitNext(event);
    }

    public void updateStatus(A2aTaskStatus newStatus) {
        this.status = newStatus;
        emitEvent(A2aTaskEvent.status(this.taskId, newStatus));
    }

    public String getTaskId() { return taskId; }
    public String getAgentName() { return agentName; }
    public Map<String, Object> getParams() { return params; }
    public Long getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public A2aTaskStatus getStatus() { return status; }
    public CancellationToken getCancellationToken() { return cancellationToken; }
    public Flux<A2aTaskEvent> getEvents() { return sink.asFlux(); }
    public Object getArtifactResult() { return artifactResult; }
    public void setArtifactResult(Object artifactResult) { this.artifactResult = artifactResult; }
}
