package com.financial.copilot.agent.core.workflow;

import com.financial.copilot.agent.core.agents.AgentNodeRouter;
import com.financial.copilot.agent.core.dag.artifact.*;
import com.financial.copilot.agent.core.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.dag.model.*;
import com.financial.copilot.agent.core.dag.planner.GraphPlannerAgent;
import com.financial.copilot.agent.core.dag.runtime.*;
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
}
