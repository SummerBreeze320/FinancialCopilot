package com.financial.copilot.controller;

import com.financial.copilot.agent.core.platform.billing.WalletBillingService;
import com.financial.copilot.agent.core.platform.conversation.ConversationService;
import com.financial.copilot.agent.core.infra.supervisor.runtime.ExecutionIdentity;
import com.financial.copilot.agent.core.infra.supervisor.runtime.ResearchRunHandle;
import com.financial.copilot.agent.core.infra.supervisor.runtime.ResearchRunRequest;
import com.financial.copilot.agent.core.infra.supervisor.runtime.ResearchRunResult;
import com.financial.copilot.agent.core.infra.security.UserPrincipal;
import com.financial.copilot.agent.core.platform.user.service.UserService;
import com.financial.copilot.agent.core.infra.workflow.FinancialResearchWorkflow;
import com.financial.copilot.common.event.ResearchStreamEvent;
import com.financial.copilot.domain.platform.conversation.entity.ConversationRun;
import com.financial.copilot.domain.platform.conversation.model.MessageStatus;
import com.financial.copilot.agent.core.infra.context.ConversationContextSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 JSON 与 SSE 共用先持久化、后运行的消息生命周期。 */
class ResearchAgentControllerTest {
    final FinancialResearchWorkflow workflow = mock(FinancialResearchWorkflow.class);
    final WalletBillingService billing = mock(WalletBillingService.class);
    final ConversationService conversations = mock(ConversationService.class);
    final ResearchAgentController controller = new ResearchAgentController(workflow, billing,
            mock(UserService.class), conversations, new ResearchRunLifecycle(conversations, billing));
    final UUID conversationId = UUID.randomUUID();
    final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            UserPrincipal.builder().userId(7L).build(), null, List.of());

    @BeforeEach
    void setup() {
        when(conversations.beginRun(eq(7L), any(), any(), anyString())).thenAnswer(call ->
                new ConversationRun(conversationId, call.getArgument(2), 1L, 2L, MessageStatus.RUNNING));
        when(conversations.snapshot(anyLong(), any(), anyString(), anyString(), any(), anyInt()))
                .thenAnswer(call -> new ConversationContextSnapshot(
                        List.of(), call.getArgument(4), call.getArgument(3), Instant.now()));
        when(workflow.run(any())).thenAnswer(call -> {
            ResearchRunRequest request = call.getArgument(0);
            if (request == null) return null;
            var result = new ResearchRunResult(
                    request.workflowRunId(),
                    request.identity(),
                    "# report",
                    "SUCCEEDED",
                    List.of(),
                    Instant.now(),
                    Instant.now()
            );
            return ResearchRunHandle.of(
                    request.workflowRunId(),
                    request.identity(),
                    Flux.just(ResearchStreamEvent.runCompleted(request.workflowRunId(), "SUCCEEDED", 1L)),
                    CompletableFuture.completedFuture(result),
                    ignored -> {}
            );
        });
    }

    private ResearchAgentController.ResearchRunRequest request() {
        var request = new ResearchAgentController.ResearchRunRequest();
        request.setPrompt("分析基金");
        return request;
    }

    @Test
    void jsonPersistsBeforeWorkflowAndReturnsServerIdentity() {
        var response = controller.run(request()).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block();
        assertThat(response.getData().getConversationId()).isEqualTo(conversationId);
        assertThat(response.getData().getReport()).isEqualTo("# report");
        var order = inOrder(conversations, workflow);
        order.verify(conversations).beginRun(eq(7L), isNull(), any(), eq("分析基金"));
        order.verify(workflow).run(any());
        verify(conversations).complete(eq(7L), any(), startsWith("7:"), eq("分析基金"), eq("# report"), anyMap());
    }

    @Test
    void sseWaitsForDurableCompletion() {
        var events = controller.streamRun(request()).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .collectList().block();
        assertThat(events).hasSize(1);
        verify(conversations).complete(eq(7L), any(), anyString(), anyString(), eq("# report"), anyMap());
    }

    @Test
    void messageCreationFailureNeverStartsModel() {
        when(conversations.beginRun(anyLong(), any(), any(), anyString())).thenThrow(new IllegalStateException("db"));
        assertThatThrownBy(() -> controller.run(request()).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block())
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(workflow);
    }

    @Test
    void failedFinalWriteCannotReturnJsonOrSseSuccess() {
        doThrow(new IllegalStateException("write failed")).when(conversations)
                .complete(anyLong(), any(), anyString(), anyString(), anyString(), anyMap());
        assertThatThrownBy(() -> controller.run(request()).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block())
                .hasMessageContaining("write failed");
        var events = controller.streamRun(request()).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).collectList().block();
        assertThat(events).extracting(ResearchStreamEvent::getType).containsExactly("run_failed");
    }

    @Test
    void planningFailureClosesAssistant() {
        doThrow(new IllegalStateException("planning")).when(workflow).run(any());
        assertThatThrownBy(() -> controller.run(request()).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block())
                .isInstanceOf(IllegalStateException.class);
        verify(conversations).fail(eq(7L), any(), any());
    }

    @Test
    void workflowReceivesImmutableIdentityAndSnapshot() {
        controller.run(request()).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block();
        ArgumentCaptor<com.financial.copilot.agent.core.infra.supervisor.runtime.ResearchRunRequest> captor =
                ArgumentCaptor.forClass(com.financial.copilot.agent.core.infra.supervisor.runtime.ResearchRunRequest.class);
        verify(workflow).run(captor.capture());
        var captured = captor.getValue();
        assertThat(captured.identity()).isNotNull();
        assertThat(captured.identity().userId()).isEqualTo(7L);
        assertThat(captured.identity().conversationId()).isEqualTo(conversationId);
        assertThat(captured.identity().tenantId()).isEqualTo(1L);
        assertThat(captured.prompt()).isEqualTo("分析基金");
    }

    @Test
    void userLimitExceededReturns429() {
        doThrow(new com.financial.copilot.common.exception.UserLimitExceededException(7L, "User limit exceeded"))
                .when(workflow).run(any());
        org.springframework.web.server.WebFilter securityFilter = (exchange, chain) ->
                chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
        org.springframework.test.web.reactive.server.WebTestClient webTestClient =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(controller)
                        .controllerAdvice(new com.financial.copilot.exception.ResearchCapacityExceptionHandler())
                        .webFilter(securityFilter)
                        .build();

        webTestClient.post().uri("/api/v1/research/runs")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(request())
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.code").isEqualTo(429);
    }

    @Test
    void systemCapacityExhaustionReturns503() {
        doThrow(new com.financial.copilot.common.exception.SystemCapacityExceededException("System capacity exhausted"))
                .when(workflow).run(any());
        org.springframework.web.server.WebFilter securityFilter = (exchange, chain) ->
                chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
        org.springframework.test.web.reactive.server.WebTestClient webTestClient =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(controller)
                        .controllerAdvice(new com.financial.copilot.exception.ResearchCapacityExceptionHandler())
                        .webFilter(securityFilter)
                        .build();

        webTestClient.post().uri("/api/v1/research/runs")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(request())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo(503);
    }

    @Test
    void queuedSseEmitsQueueLifecycleEvents() {
        var identity = new ExecutionIdentity(1L, 7L, conversationId);
        var result = new ResearchRunResult(
                conversationId.toString(), identity, "# report", "SUCCEEDED", List.of(),
                Instant.now(), Instant.now()
        );

        doAnswer(call -> {
            com.financial.copilot.agent.core.infra.supervisor.runtime.ResearchRunRequest req = call.getArgument(0);
            return ResearchRunHandle.of(req.workflowRunId(), identity,
                    Flux.just(
                            ResearchStreamEvent.runQueued(req.workflowRunId(), conversationId.toString(), "QUEUED_CONVERSATION", 1),
                            ResearchStreamEvent.runAdmitted(req.workflowRunId(), conversationId.toString()),
                            ResearchStreamEvent.runStarted(req.workflowRunId(), conversationId.toString()),
                            ResearchStreamEvent.runCompleted(req.workflowRunId(), "SUCCEEDED", 100L)
                    ),
                    CompletableFuture.completedFuture(result),
                    ignored -> {});
        }).when(workflow).run(any());

        List<ResearchStreamEvent> events = controller.streamRun(request())
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .collectList()
                .block();

        assertThat(events).extracting(ResearchStreamEvent::getType)
                .containsExactly("run_queued", "run_admitted", "run_started", "run_completed");
        assertThat(events.get(0).getStatus()).isEqualTo("QUEUED");
        assertThat(events.get(1).getStatus()).isEqualTo("ADMITTED");
        assertThat(events.get(2).getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void sseCancellationCancelsHandle() {
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        var identity = new ExecutionIdentity(1L, 7L, conversationId);
        doAnswer(call -> {
            com.financial.copilot.agent.core.infra.supervisor.runtime.ResearchRunRequest req = call.getArgument(0);
            return ResearchRunHandle.of(req.workflowRunId(), identity,
                    Flux.never(),
                    new CompletableFuture<>(),
                    reason -> cancelled.set(true));
        }).when(workflow).run(any());

        var disposable = controller.streamRun(request())
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .subscribe();

        disposable.dispose();
        assertThat(cancelled.get()).isTrue();
    }

    @Test
    void healthCheckReturnsCapacityDetails() {
        com.financial.copilot.agent.core.infra.supervisor.admission.ConversationAdmissionController admission = mock(com.financial.copilot.agent.core.infra.supervisor.admission.ConversationAdmissionController.class);
        when(admission.getActiveRunCount()).thenReturn(3);
        when(admission.getQueuedRunCount()).thenReturn(0);
        when(admission.properties()).thenReturn(com.financial.copilot.agent.core.infra.supervisor.admission.RuntimeCapacityProperties.builder()
                .maxActiveRunsGlobal(10)
                .maxQueuedRuns(20)
                .maxLlmConcurrency(8)
                .build());
        when(workflow.getAdmissionController()).thenReturn(admission);

        var response = controller.healthCheck();
        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData()).containsEntry("status", "UP");
        @SuppressWarnings("unchecked")
        Map<String, Object> capacity = (Map<String, Object>) response.getData().get("capacity");
        assertThat(capacity).containsEntry("activeRuns", 3);
        assertThat(capacity).containsEntry("queuedRuns", 0);
        assertThat(capacity).containsEntry("maxActiveRuns", 10);
        assertThat(capacity).containsEntry("maxQueuedRuns", 20);
        assertThat(capacity).containsEntry("maxLlmConcurrency", 8);
    }
}
