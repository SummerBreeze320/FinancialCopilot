package com.financial.copilot.controller;

import com.financial.copilot.agent.core.platform.billing.WalletBillingService;
import com.financial.copilot.agent.core.platform.conversation.ConversationService;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunHandle;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunResult;
import com.financial.copilot.agent.core.infra.security.UserPrincipal;
import com.financial.copilot.agent.core.platform.user.service.UserService;
import com.financial.copilot.agent.core.infra.workflow.FinancialResearchWorkflow;
import com.financial.copilot.common.event.ResearchStreamEvent;
import com.financial.copilot.domain.platform.conversation.entity.ConversationRun;
import com.financial.copilot.domain.platform.conversation.model.MessageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Flux;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证 JSON 与 SSE 共用先持久化、后运行的消息生命周期。 */
class ResearchAgentControllerTest {
    final FinancialResearchWorkflow workflow = mock(FinancialResearchWorkflow.class);
    final WalletBillingService billing = mock(WalletBillingService.class);
    final ConversationService conversations = mock(ConversationService.class);
    final ResearchAgentController controller = new ResearchAgentController(workflow, billing,
            mock(UserService.class), conversations, new ResearchRunLifecycle(conversations));
    final UUID conversationId = UUID.randomUUID();
    final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            UserPrincipal.builder().userId(7L).build(), null, List.of());

    @BeforeEach
    void setup() {
        when(conversations.beginRun(eq(7L), any(), any(), anyString())).thenAnswer(call ->
                new ConversationRun(conversationId, call.getArgument(2), 1L, 2L, MessageStatus.RUNNING));
        when(workflow.run(any())).thenAnswer(call -> {
            GraphRunRequest request = call.getArgument(0);
            var graph = new ExecutionGraph("g");
            Artifact<?> report = Artifact.of("r", ArtifactType.FINAL_REPORT, "s",
                    FinalSynthesisReport.of("summary", "# report"));
            var result = new GraphRunResult(request.runId(), graph, Map.of(), Map.of("s", report),
                    Instant.now(), Instant.now());
            return new GraphRunHandle(request.runId(),
                    Flux.just(ResearchStreamEvent.runCompleted(request.runId(), "SUCCEEDED", 1L)),
                    CompletableFuture.completedFuture(result), ignored -> {});
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
                .hasRootCauseMessage("write failed");
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
}
