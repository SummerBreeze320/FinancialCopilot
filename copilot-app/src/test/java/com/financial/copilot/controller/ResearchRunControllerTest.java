package com.financial.copilot.controller;

import com.financial.copilot.agent.core.infra.runtime.ResearchRunHandle;
import com.financial.copilot.agent.core.infra.security.UserPrincipal;
import com.financial.copilot.agent.core.infra.workflow.FinancialResearchWorkflow;
import com.financial.copilot.agent.core.platform.billing.WalletBillingService;
import com.financial.copilot.agent.core.platform.conversation.ConversationService;
import com.financial.copilot.common.event.ResearchStreamEvent;
import com.financial.copilot.domain.platform.conversation.entity.ConversationRun;
import com.financial.copilot.domain.platform.execution.entity.ResearchRunEvent;
import com.financial.copilot.domain.platform.execution.port.ResearchExecutionPort;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchRunControllerTest {
    @Test
    void foreignOrMissingRunReturnsNotFound() {
        FinancialResearchWorkflow workflow = mock(FinancialResearchWorkflow.class);
        when(workflow.findRun(8L, "owned-by-7")).thenReturn(Optional.empty());
        ResearchRunController controller = new ResearchRunController(workflow, mock(WalletBillingService.class),
                mock(ConversationService.class));
        var auth = new UsernamePasswordAuthenticationToken(UserPrincipal.builder().userId(8L).build(), null, List.of());
        assertThatThrownBy(() -> controller.status("owned-by-7")
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block())
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    @Test
    void cancelInvokesWorkflowCancel() {
        FinancialResearchWorkflow workflow = mock(FinancialResearchWorkflow.class);
        ConversationService conversationService = mock(ConversationService.class);
        UUID runId = UUID.randomUUID();
        when(conversationService.findRun(eq(8L), eq(runId)))
                .thenReturn(Optional.of(mock(com.financial.copilot.domain.platform.conversation.entity.ConversationRun.class)));
        when(workflow.cancel(eq(8L), eq(runId.toString()), anyString())).thenReturn(true);
        ResearchRunController controller = new ResearchRunController(workflow, mock(WalletBillingService.class),
                conversationService);
        var auth = new UsernamePasswordAuthenticationToken(UserPrincipal.builder().userId(8L).build(), null, List.of());
        var res = controller.cancel(runId.toString())
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block();
        assertThat(res.getData().get("status")).isEqualTo("CANCELLED");
        verify(workflow).cancel(eq(8L), eq(runId.toString()), anyString());
    }

    @Test
    void replayEventsReplaysPersistedEventsForCompletedRun() {
        FinancialResearchWorkflow workflow = mock(FinancialResearchWorkflow.class);
        ConversationService conversationService = mock(ConversationService.class);
        ResearchExecutionPort executionPort = mock(ResearchExecutionPort.class);
        UUID runId = UUID.randomUUID();

        when(conversationService.findRun(eq(8L), eq(runId)))
                .thenReturn(Optional.of(mock(com.financial.copilot.domain.platform.conversation.entity.ConversationRun.class)));
        when(workflow.findRun(eq(8L), eq(runId.toString()))).thenReturn(Optional.empty());

        ResearchRunEvent event1 = new ResearchRunEvent(runId, 8L, 1L, "subagent_started", "{\"type\":\"subagent_started\"}", Instant.now());
        ResearchRunEvent event2 = new ResearchRunEvent(runId, 8L, 2L, "subagent_completed", "{\"type\":\"subagent_completed\"}", Instant.now());
        when(executionPort.getEventsAfter(8L, runId, 0L)).thenReturn(List.of(event1, event2));

        ResearchRunController controller = new ResearchRunController(workflow, mock(WalletBillingService.class),
                conversationService, executionPort, new com.fasterxml.jackson.databind.ObjectMapper());
        var auth = new UsernamePasswordAuthenticationToken(UserPrincipal.builder().userId(8L).build(), null, List.of());

        List<ResearchStreamEvent> results = controller.replayEvents(runId.toString(), "0")
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .collectList()
                .block();

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getSequenceNo()).isEqualTo(1L);
        assertThat(results.get(1).getSequenceNo()).isEqualTo(2L);
    }

    @Test
    void replayEventsConcatenatesLiveStreamWithoutDuplicatesForActiveRun() {
        FinancialResearchWorkflow workflow = mock(FinancialResearchWorkflow.class);
        ConversationService conversationService = mock(ConversationService.class);
        ResearchExecutionPort executionPort = mock(ResearchExecutionPort.class);
        UUID runId = UUID.randomUUID();

        when(conversationService.findRun(eq(8L), eq(runId)))
                .thenReturn(Optional.of(mock(com.financial.copilot.domain.platform.conversation.entity.ConversationRun.class)));

        ResearchRunEvent persisted1 = new ResearchRunEvent(runId, 8L, 1L, "subagent_started", "{\"type\":\"subagent_started\"}", Instant.now());
        when(executionPort.getEventsAfter(8L, runId, 0L)).thenReturn(List.of(persisted1));

        ResearchRunHandle activeHandle = mock(ResearchRunHandle.class);
        ResearchStreamEvent liveDup = ResearchStreamEvent.builder().type("subagent_started").sequenceNo(1L).build();
        ResearchStreamEvent liveNew = ResearchStreamEvent.builder().type("subagent_completed").sequenceNo(2L).build();
        when(activeHandle.events()).thenReturn(reactor.core.publisher.Flux.just(liveDup, liveNew));
        when(workflow.findRun(eq(8L), eq(runId.toString()))).thenReturn(Optional.of(activeHandle));

        ResearchRunController controller = new ResearchRunController(workflow, mock(WalletBillingService.class),
                conversationService, executionPort, new com.fasterxml.jackson.databind.ObjectMapper());
        var auth = new UsernamePasswordAuthenticationToken(UserPrincipal.builder().userId(8L).build(), null, List.of());

        List<ResearchStreamEvent> results = controller.replayEvents(runId.toString(), "0")
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .collectList()
                .block();

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getSequenceNo()).isEqualTo(1L);
        assertThat(results.get(1).getSequenceNo()).isEqualTo(2L);
    }
}
