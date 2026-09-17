package com.financial.copilot.agent.core.workflow;

import com.financial.copilot.agent.core.agents.AgentNodeRouter;
import com.financial.copilot.agent.core.dag.artifact.*;
import com.financial.copilot.agent.core.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.dag.model.*;
import com.financial.copilot.agent.core.dag.planner.GraphPlannerAgent;
import com.financial.copilot.agent.core.dag.runtime.*;
import com.financial.copilot.agent.tools.configured.workspace.ToolWorkspacePayload;
import com.financial.copilot.common.event.ResearchStreamEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

        GraphRunResult result=workflow.run(new GraphRunRequest("run",7L,UUID.randomUUID(), null, "session","分析基金",false,null,ignored->{},RunMode.SYNC))
                .completion().get(2, TimeUnit.SECONDS);

        assertThat(result.artifacts().values()).extracting(Artifact::type).contains(ArtifactType.FINAL_REPORT);
        verify(router).execute(any(),any(),argThat(context->context.request().userId().equals(7L)));
    }

    @Test
    void workflowEmitsWorkspaceEventAndExecutesSuccessfully() throws Exception {
        AgentNodeRouter router = mock(AgentNodeRouter.class);
        GraphPlannerAgent planner = mock(GraphPlannerAgent.class);
        ExecutionGraph graph = new ExecutionGraph("workspace-graph");
        graph.addNode(GraphNode.builder().nodeId("analyze").taskType("FUND_ANALYSIS").outputType(ArtifactType.FUND_RESEARCH).build());
        when(planner.plan(any())).thenReturn(graph);

        ToolWorkspacePayload mockWorkspace = ToolWorkspacePayload.builder()
                .id("ws-test-1")
                .type("FUND_ANALYSIS")
                .name("基金分析工作台")
                .components(List.of())
                .build();

        when(router.execute(any(), any(), any())).thenAnswer(invocation -> {
            NodeExecutionContext ctx = invocation.getArgument(2);
            ctx.publishWorkspace(mockWorkspace);
            return Artifact.of("analyze-art", ArtifactType.FUND_RESEARCH, "analyze", "dummy-result");
        });

        FinancialResearchWorkflow workflow = new FinancialResearchWorkflow(router, planner, null, null, null, ReplanPolicy.never(), null);
        List<ResearchStreamEvent> receivedEvents = new ArrayList<>();

        GraphRunRequest request = new GraphRunRequest(
                "run-ws-1",
                8L,
                UUID.randomUUID(),
                null,
                "session-ws",
                "分析 000001",
                false,
                null,
                ignoredUsage -> {},
                RunMode.SYNC
        );

        GraphRunHandle handle = workflow.run(request);
        handle.events().subscribe(receivedEvents::add);

        GraphRunResult result = handle.completion().get(2, TimeUnit.SECONDS);

        assertThat(result.artifacts().values()).extracting(Artifact::type).contains(ArtifactType.FUND_RESEARCH);
        assertThat(receivedEvents).anyMatch(event ->
                "workspace".equals(event.getType())
                && event.getPayload() instanceof ToolWorkspacePayload payload
                && "FUND_ANALYSIS".equals(payload.type())
        );
    }
}
