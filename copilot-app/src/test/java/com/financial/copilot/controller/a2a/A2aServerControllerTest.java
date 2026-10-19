package com.financial.copilot.controller.a2a;

import com.financial.copilot.agent.core.business.agents.a2a.A2aAgentRegistry;
import com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions;
import com.financial.copilot.agent.core.business.agents.a2a.manager.A2aTaskManager;
import com.financial.copilot.agent.core.business.agents.a2a.manager.A2aTaskSession;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aJsonRpcRequest;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskEvent;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("A2aServerController WebFlux 接口测试")
class A2aServerControllerTest {

    private A2aAgentRegistry agentRegistry;
    private A2aTaskManager taskManager;
    private WebTestClient webClient;

    @BeforeEach
    void setUp() {
        agentRegistry = mock(A2aAgentRegistry.class);
        taskManager = mock(A2aTaskManager.class);
        A2aServerController controller = new A2aServerController(agentRegistry, taskManager);
        webClient = WebTestClient.bindToController(controller).build();
    }

    @Test
    @DisplayName("GET /.well-known/agent.json 自动返回主 AgentCard")
    void testGetWellKnownAgentCard() {
        when(agentRegistry.getAllAgentCards()).thenReturn(List.of(AgentCardDefinitions.fundScreenerCard()));

        webClient.get().uri("/.well-known/agent.json")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.name").isEqualTo("FundScreenerAgent");
    }

    @Test
    @DisplayName("GET /api/v1/a2a/agents 返回所有已注册智能体名片")
    void testListAgents() {
        when(agentRegistry.getAllAgentCards()).thenReturn(List.of(
                AgentCardDefinitions.fundScreenerCard(),
                AgentCardDefinitions.stockAnalyzerCard()
        ));

        webClient.get().uri("/api/v1/a2a/agents")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2);
    }

    @Test
    @DisplayName("GET /api/v1/a2a/agents/{name}/.well-known/agent.json 查询指定智能体名片")
    void testGetAgentCardByName() {
        when(agentRegistry.findCardByName("StockAnalyzerAgent"))
                .thenReturn(Optional.of(AgentCardDefinitions.stockAnalyzerCard()));

        webClient.get().uri("/api/v1/a2a/agents/StockAnalyzerAgent/.well-known/agent.json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("StockAnalyzerAgent");
    }

    @Test
    @DisplayName("POST /api/v1/a2a/agents/{name}/rpc 成功提交 tasks.create")
    void testCreateTaskRpc() {
        A2aTaskSession session = new A2aTaskSession("task-123", "FundScreenerAgent", Map.of("prompt", "测试"), 100L);
        when(taskManager.createTask(eq("FundScreenerAgent"), any(), any())).thenReturn(session);

        A2aJsonRpcRequest request = new A2aJsonRpcRequest("2.0", "req-1", "tasks.create", Map.of("prompt", "测试"));

        webClient.post().uri("/api/v1/a2a/agents/FundScreenerAgent/rpc")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.taskId").isEqualTo("task-123")
                .jsonPath("$.result.status").isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("POST /api/v1/a2a/agents/{name}/rpc 支持 tasks.cancel 和 tasks.get_task")
    void testCancelAndGetTaskRpc() {
        when(taskManager.cancelTask(eq("task-123"), any())).thenReturn(true);
        A2aJsonRpcRequest cancelReq = new A2aJsonRpcRequest("2.0", "req-2", "tasks.cancel", Map.of("taskId", "task-123"));

        webClient.post().uri("/api/v1/a2a/agents/FundScreenerAgent/rpc")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(cancelReq)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.cancelled").isEqualTo(true);

        A2aTaskSession session = new A2aTaskSession("task-123", "FundScreenerAgent", Map.of("prompt", "测试"), 100L);
        session.setArtifactResult(Map.of("summary", "done"));
        when(taskManager.getTask("task-123")).thenReturn(Optional.of(session));

        A2aJsonRpcRequest getReq = new A2aJsonRpcRequest("2.0", "req-3", "tasks.get_task", Map.of("taskId", "task-123"));
        webClient.post().uri("/api/v1/a2a/agents/FundScreenerAgent/rpc")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(getReq)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.taskId").isEqualTo("task-123")
                .jsonPath("$.result.result.summary").isEqualTo("done");
    }

    @Test
    @DisplayName("GET /api/v1/a2a/tasks/{taskId}/events 支持 SSE 事件流")
    void testStreamTaskEvents() {
        when(taskManager.streamEvents("task-123")).thenReturn(
                Flux.just(A2aTaskEvent.status("task-123", A2aTaskStatus.WORKING))
        );

        webClient.get().uri("/api/v1/a2a/tasks/task-123/events")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);
    }
}
