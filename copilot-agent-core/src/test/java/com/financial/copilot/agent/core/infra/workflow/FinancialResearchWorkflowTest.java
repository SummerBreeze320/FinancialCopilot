package com.financial.copilot.agent.core.infra.workflow;

import com.financial.copilot.agent.core.business.agents.AgentNodeRouter;
import com.financial.copilot.agent.core.infra.dag.artifact.*;
import com.financial.copilot.agent.core.infra.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.infra.dag.model.*;
import com.financial.copilot.agent.core.infra.dag.planner.GraphPlannerAgent;
import com.financial.copilot.agent.core.infra.dag.runtime.*;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinancialResearchWorkflowTest {
    @Test
    void unifiedEntryUsesNativeAgentRouter() throws Exception {
        AgentNodeRouter router=mock(AgentNodeRouter.class);
        GraphPlannerAgent planner=mock(GraphPlannerAgent.class);
        ExecutionGraph graph=new ExecutionGraph("native");
        graph.addNode(GraphNode.builder().nodeId("report").taskType("SYNTHESIS").outputType(ArtifactType.FINAL_REPORT).build());
        when(planner.plan(any())).thenReturn(graph);
        when(router.execute(any(),any(),any())).thenAnswer(call->Artifact.of("report",ArtifactType.FINAL_REPORT,"report",FinalSynthesisReport.of("summary","# report")));
        FinancialResearchWorkflow workflow=new FinancialResearchWorkflow(router,planner,null,null,null,ReplanPolicy.never(),null);

        GraphRunResult result=workflow.run(new GraphRunRequest("run",7L,java.util.UUID.randomUUID(), null, "session","分析基金",false,null,ignored->{},RunMode.SYNC))
                .completion().get(2, TimeUnit.SECONDS);

        assertThat(result.artifacts().values()).extracting(Artifact::type).contains(ArtifactType.FINAL_REPORT);
        verify(router).execute(any(),any(),argThat(context->context.request().userId().equals(7L)));
    }

    @Test
    void unifiedEntryDelegatesToSupervisorWorkflowWhenPresent() throws Exception {
        AgentNodeRouter router = mock(AgentNodeRouter.class);
        GraphPlannerAgent planner = mock(GraphPlannerAgent.class);
        com.financial.copilot.agent.core.infra.supervisor.SupervisorWorkflow supervisor = mock(com.financial.copilot.agent.core.infra.supervisor.SupervisorWorkflow.class);
        ExecutionGraph graph = new ExecutionGraph("supervisor-graph");
        when(planner.plan(any())).thenReturn(graph);

        FinalSynthesisReport reportPayload = FinalSynthesisReport.of("标题", "摘要", "正文", java.util.Map.of(), java.util.List.of());
        Artifact<FinalSynthesisReport> reportArt = Artifact.of("rep-1", ArtifactType.FINAL_REPORT, "agg", reportPayload);
        GraphRunResult expectedResult = new GraphRunResult(
                "run-sup", graph, java.util.Map.of(), java.util.Map.of("rep-1", reportArt), java.time.Instant.now(), java.time.Instant.now()
        );
        GraphRunHandle handle = new GraphRunHandle(
                "run-sup", reactor.core.publisher.Flux.empty(), java.util.concurrent.CompletableFuture.completedFuture(expectedResult), ignored -> {}
        );
        when(supervisor.runHandle(any(), eq(graph))).thenReturn(handle);

        FinancialResearchWorkflow workflow = new FinancialResearchWorkflow(
                router, planner, null, null, null, ReplanPolicy.never(), null, null, null, null, null, supervisor
        );

        GraphRunRequest request = new GraphRunRequest("run-sup", 7L, java.util.UUID.randomUUID(), null, "session", "分析基金", false, null, ignored -> {}, RunMode.SYNC);
        GraphRunResult result = workflow.run(request).completion().get(2, TimeUnit.SECONDS);

        assertThat(result.runId()).isEqualTo("run-sup");
        assertThat(result.artifacts()).containsKey("rep-1");
        verify(supervisor).runHandle(any(), eq(graph));
    }
}
