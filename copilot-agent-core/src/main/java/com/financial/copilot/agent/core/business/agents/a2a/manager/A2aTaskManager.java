package com.financial.copilot.agent.core.business.agents.a2a.manager;

import com.financial.copilot.agent.core.business.agents.a2a.A2aAgentRegistry;
import com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskEvent;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskStatus;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.infra.dag.runtime.RunMode;
import com.financial.copilot.agent.core.infra.thread.VirtualThreadSchedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** A2A 长时任务管理与异步执行器 */
@Component
public class A2aTaskManager {

    private static final Logger log = LoggerFactory.getLogger(A2aTaskManager.class);

    private final A2aAgentRegistry registry;
    private final Map<String, A2aTaskSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public A2aTaskManager(A2aAgentRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public A2aTaskSession createTask(String agentName, Map<String, Object> params, Long userId) {
        A2aExecutableAgent agent = registry.getAgents().stream()
                .filter(a -> a.agentCard() != null && agentName.equalsIgnoreCase(a.agentCard().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Target A2A Agent not found: " + agentName));

        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        A2aTaskSession session = new A2aTaskSession(taskId, agentName, params, userId);
        sessions.put(taskId, session);

        session.updateStatus(A2aTaskStatus.WORKING);

        // Execute asynchronously on virtual thread
        VirtualThreadSchedulers.executor().execute(() -> {
            try {
                String prompt = params != null && params.get("prompt") != null ? String.valueOf(params.get("prompt")) : "";
                GraphRunRequest request = new GraphRunRequest(
                        taskId, userId, UUID.randomUUID(), null, "session-" + taskId,
                        prompt, false, null, null, RunMode.STREAM
                );
                NodeExecutionContext context = new NodeExecutionContext(
                        request, taskId, new ArtifactStore(), session.getCancellationToken()
                );
                GraphNode node = GraphNode.builder()
                        .nodeId(taskId)
                        .taskType(agent.agentCard().skills().getFirst().tags().getFirst())
                        .params(params != null ? params : Map.of())
                        .build();

                Artifact<?> artifact = agent.execute(node, NodeInput.empty(), context);
                if (artifact != null) {
                    session.setArtifactResult(artifact.payload());
                    session.emitEvent(A2aTaskEvent.artifact(taskId, artifact.payload()));
                }
                session.updateStatus(A2aTaskStatus.COMPLETED);
            } catch (Exception e) {
                log.error("A2A Task execution failed: taskId={}", taskId, e);
                session.emitEvent(A2aTaskEvent.error(taskId, e.getMessage()));
                session.updateStatus(A2aTaskStatus.FAILED);
            }
        });

        return session;
    }

    public Optional<A2aTaskSession> getTask(String taskId) {
        return Optional.ofNullable(sessions.get(taskId));
    }

    public boolean cancelTask(String taskId, String reason) {
        A2aTaskSession session = sessions.get(taskId);
        if (session != null && session.getStatus() == A2aTaskStatus.WORKING) {
            session.getCancellationToken().cancel(reason);
            session.updateStatus(A2aTaskStatus.CANCELLED);
            return true;
        }
        return false;
    }

    public Flux<A2aTaskEvent> streamEvents(String taskId) {
        A2aTaskSession session = sessions.get(taskId);
        if (session == null) {
            return Flux.error(new IllegalArgumentException("Task not found: " + taskId));
        }
        return session.getEvents();
    }
}
