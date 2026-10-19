package com.financial.copilot.controller.a2a;

import com.financial.copilot.agent.core.business.agents.a2a.A2aAgentRegistry;
import com.financial.copilot.agent.core.business.agents.a2a.manager.A2aTaskManager;
import com.financial.copilot.agent.core.business.agents.a2a.manager.A2aTaskSession;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aJsonRpcRequest;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aJsonRpcResponse;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskEvent;
import com.financial.copilot.config.security.SecurityUtils;
import io.a2a.spec.AgentCard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <h1>A2A 标准协议服务端控制器 (A2aServerController)</h1>
 * <p>
 * 提供标准 AgentCard 自发现 (.well-known/agent.json)、JSON-RPC 2.0 任务调度及 SSE 事件流输出。
 * </p>
 *
 * @author FinancialCopilot
 */
@RestController
public class A2aServerController {

    private final A2aAgentRegistry registry;
    private final A2aTaskManager taskManager;

    @Autowired
    public A2aServerController(A2aAgentRegistry registry, A2aTaskManager taskManager) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
    }

    @GetMapping(value = "/.well-known/agent.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<AgentCard>> getRootAgentCard() {
        List<AgentCard> cards = registry.getAllAgentCards();
        if (cards.isEmpty()) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        return Mono.just(ResponseEntity.ok(cards.getFirst()));
    }

    @GetMapping(value = "/api/v1/a2a/agents", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<AgentCard>> listAgents() {
        return Mono.just(registry.getAllAgentCards());
    }

    @GetMapping(value = "/api/v1/a2a/agents/{agentName}/.well-known/agent.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<AgentCard>> getAgentCard(@PathVariable("agentName") String agentName) {
        return Mono.justOrEmpty(registry.findCardByName(agentName))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping(value = "/api/v1/a2a/agents/{agentName}/rpc", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<A2aJsonRpcResponse> handleRpc(
            @PathVariable("agentName") String agentName,
            @RequestBody A2aJsonRpcRequest request
    ) {
        if (request == null || request.method() == null) {
            return Mono.just(A2aJsonRpcResponse.error(null, -32600, "Invalid Request"));
        }

        return SecurityUtils.getCurrentUserId().defaultIfEmpty(100L).flatMap(userId -> {
            switch (request.method()) {
                case "tasks.create" -> {
                    try {
                        A2aTaskSession session = taskManager.createTask(agentName, request.params(), userId);
                        return Mono.just(A2aJsonRpcResponse.success(request.id(), Map.of(
                                "taskId", session.getTaskId(),
                                "status", session.getStatus().name(),
                                "eventsUrl", "/api/v1/a2a/tasks/" + session.getTaskId() + "/events"
                        )));
                    } catch (Exception e) {
                        return Mono.just(A2aJsonRpcResponse.error(request.id(), -32000, e.getMessage()));
                    }
                }
                case "tasks.cancel" -> {
                    String taskId = request.params() != null && request.params().get("taskId") != null
                            ? String.valueOf(request.params().get("taskId"))
                            : null;
                    boolean cancelled = taskId != null && taskManager.cancelTask(taskId, "Cancelled via JSON-RPC");
                    return Mono.just(A2aJsonRpcResponse.success(request.id(), Map.of("cancelled", cancelled)));
                }
                case "tasks.get_task" -> {
                    String taskId = request.params() != null && request.params().get("taskId") != null
                            ? String.valueOf(request.params().get("taskId"))
                            : null;
                    return Mono.justOrEmpty(taskId != null ? taskManager.getTask(taskId) : null)
                            .map(s -> A2aJsonRpcResponse.success(request.id(), Map.of(
                                    "taskId", s.getTaskId(),
                                    "status", s.getStatus().name(),
                                    "result", s.getArtifactResult() != null ? s.getArtifactResult() : Map.of()
                            )))
                            .defaultIfEmpty(A2aJsonRpcResponse.error(request.id(), -32004, "Task not found: " + taskId));
                }
                default -> {
                    return Mono.just(A2aJsonRpcResponse.error(request.id(), -32601, "Method not found: " + request.method()));
                }
            }
        });
    }

    @GetMapping(value = "/api/v1/a2a/tasks/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<A2aTaskEvent> streamTaskEvents(@PathVariable("taskId") String taskId) {
        return taskManager.streamEvents(taskId);
    }
}
