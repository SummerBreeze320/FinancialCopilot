package com.financial.copilot.agent.core.infra.workflow;

import com.financial.copilot.agent.core.infra.admission.ConversationAdmissionController;
import com.financial.copilot.agent.core.infra.admission.ConversationBusyException;
import com.financial.copilot.agent.core.infra.admission.RuntimeCapacityProperties;
import com.financial.copilot.agent.core.infra.agent.ResearchSupervisor;
import com.financial.copilot.agent.core.infra.event.FrameworkEventBridge;
import com.financial.copilot.agent.core.infra.runtime.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class FinancialResearchWorkflowSupervisorTest {

    private ResearchSupervisor supervisor;
    private ConversationAdmissionController admissionController;
    private SupervisorRunRegistry registry;
    private FrameworkEventBridge eventBridge;
    private FinancialResearchWorkflow workflow;

    @BeforeEach
    void setUp() {
        supervisor = Mockito.mock(ResearchSupervisor.class);
        admissionController = new ConversationAdmissionController(new RuntimeCapacityProperties());
        registry = new SupervisorRunRegistry();
        eventBridge = new FrameworkEventBridge();
        workflow = new FinancialResearchWorkflow(
                supervisor,
                admissionController,
                registry,
                eventBridge
        );
    }

    @Test
    void runsSupervisorFlowAndReleasesAdmissionUponCompletion() throws Exception {
        Long userId = 10L;
        UUID conversationId = UUID.randomUUID();
        String runId = UUID.randomUUID().toString();

        CompletableFuture<ResearchRunResult> executionFuture = new CompletableFuture<>();
        when(supervisor.execute(any(), eq("分析易方达蓝筹")))
                .thenAnswer(inv -> executionFuture.get());

        ResearchRunRequest request = new ResearchRunRequest(
                runId,
                1L,
                userId,
                conversationId,
                1L,
                "分析易方达蓝筹",
                false,
                RunMode.SYNC
        );

        ResearchRunHandle handle = workflow.run(request);
        assertThat(handle).isNotNull();

        // While running, conversation is occupied
        assertThatThrownBy(() -> workflow.run(request))
                .isInstanceOf(ConversationBusyException.class);

        // Complete supervisor execution
        executionFuture.complete(new ResearchRunResult(
                runId,
                new ExecutionIdentity(1L, userId, conversationId),
                "易方达蓝筹是一只偏股混合型基金，最新年化回报显著。",
                "SUCCEEDED",
                List.of("ev-01"),
                Instant.now(),
                Instant.now()
        ));

        // Await completion
        ResearchRunResult result = handle.completion().get();
        assertThat(result).isNotNull();
        assertThat(result.workflowRunId()).isEqualTo(runId);
        assertThat(result.finalReport()).contains("易方达蓝筹是一只偏股混合型基金");

        // After completion, admission must be released
        assertThat(admissionController.getGlobalActiveCount()).isEqualTo(0);
    }

    @Test
    void releasesAdmissionWhenRunIsCancelled() {
        Long userId = 20L;
        UUID conversationId = UUID.randomUUID();
        String runId = UUID.randomUUID().toString();

        CompletableFuture<ResearchRunResult> slowExecution = new CompletableFuture<>();
        when(supervisor.execute(any(), any()))
                .thenAnswer(inv -> slowExecution.get());

        ResearchRunRequest request = new ResearchRunRequest(
                runId,
                1L,
                userId,
                conversationId,
                2L,
                "长耗时分析任务",
                false,
                RunMode.STREAM
        );

        ResearchRunHandle handle = workflow.run(request);
        assertThat(admissionController.getGlobalActiveCount()).isEqualTo(1);

        // Cancel
        boolean cancelled = workflow.cancel(userId, runId, "User cancelled");
        assertThat(cancelled).isTrue();

        // Admission released
        assertThat(admissionController.getGlobalActiveCount()).isEqualTo(0);
    }
}
