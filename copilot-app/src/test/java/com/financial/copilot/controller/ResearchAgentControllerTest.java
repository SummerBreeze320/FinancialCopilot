package com.financial.copilot.controller;

import com.financial.copilot.agent.core.billing.WalletBillingService;
import com.financial.copilot.agent.core.dag.artifact.*;
import com.financial.copilot.agent.core.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.runtime.*;
import com.financial.copilot.agent.core.memory.*;
import com.financial.copilot.agent.core.security.UserPrincipal;
import com.financial.copilot.agent.core.user.service.UserService;
import com.financial.copilot.agent.core.workflow.FinancialResearchWorkflow;
import com.financial.copilot.common.event.ResearchStreamEvent;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Flux;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResearchAgentControllerTest {
    FinancialResearchWorkflow workflow = mock(FinancialResearchWorkflow.class);
    WalletBillingService billing = mock(WalletBillingService.class);
    UserService users = mock(UserService.class);
    ResearchAgentController controller = new ResearchAgentController(workflow, billing, users,
            mock(ShortTermMemoryService.class), mock(LongTermMemoryService.class));
    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            UserPrincipal.builder().userId(7L).build(), null, List.of());

    @BeforeEach
    void stubRun() {
        ExecutionGraph graph = new ExecutionGraph("g");
        Artifact<?> report = Artifact.of("r", ArtifactType.FINAL_REPORT, "s",
                FinalSynthesisReport.of("summary", "# report"));
        GraphRunResult result = new GraphRunResult("run", graph, Map.of(), Map.of("s", report), Instant.now(), Instant.now());
        when(workflow.run(any())).thenReturn(new GraphRunHandle("run",
                Flux.just(ResearchStreamEvent.runCompleted("run", "SUCCEEDED", 1L)),
                CompletableFuture.completedFuture(result), ignored -> {}));
    }

    @Test
    void jsonRunUsesAuthenticatedOwnedRunEntry() {
        ResearchAgentController.ResearchRunRequest request = new ResearchAgentController.ResearchRunRequest();
        request.setPrompt("分析基金"); request.setSessionId("client");
        var response = controller.run(request)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block();
        assertThat(response.getData().getReport()).isEqualTo("# report");
        assertThat(response.getData().getRunId()).isEqualTo("run");
        ArgumentCaptor<GraphRunRequest> captor = ArgumentCaptor.forClass(GraphRunRequest.class);
        verify(workflow).run(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(7L);
        assertThat(captor.getValue().sessionId()).startsWith("7:").doesNotContain("client");
        assertThat(captor.getValue().mode()).isEqualTo(RunMode.SYNC);
    }

    @Test
    void sseRunUsesSameRunEntry() {
        ResearchAgentController.ResearchRunRequest request = new ResearchAgentController.ResearchRunRequest();
        request.setPrompt("分析基金"); request.setSessionId("client");
        var events = controller.streamRun(request)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).collectList().block();
        assertThat(events).hasSize(1);
        ArgumentCaptor<GraphRunRequest> captor = ArgumentCaptor.forClass(GraphRunRequest.class);
        verify(workflow).run(captor.capture());
        assertThat(captor.getValue().mode()).isEqualTo(RunMode.STREAM);
    }
}
