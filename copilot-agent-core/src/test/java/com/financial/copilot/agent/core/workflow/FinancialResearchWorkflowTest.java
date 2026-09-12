package com.financial.copilot.agent.core.workflow;

import com.financial.copilot.agent.core.agents.*;
import com.financial.copilot.agent.core.dag.artifact.*;
import com.financial.copilot.agent.core.dag.artifact.payload.*;
import com.financial.copilot.agent.core.dag.planner.GraphPlanner;
import com.financial.copilot.agent.core.dag.runtime.*;
import com.financial.copilot.common.event.ResearchStreamEvent;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinancialResearchWorkflowTest {
    @Test
    void oneRunEntryDrivesSyncResultAndRuntimeEvents() throws Exception {
        ScreenerAgent screener = mock(ScreenerAgent.class);
        AnalyzerAgent analyzer = mock(AnalyzerAgent.class);
        ComparatorAgent comparator = mock(ComparatorAgent.class);
        ReportSynthesizer synthesizer = mock(ReportSynthesizer.class);
        when(screener.execute(any(), any(), any())).thenAnswer(i -> Artifact.of("pool", ArtifactType.FUND_POOL,
                i.<com.financial.copilot.agent.core.dag.model.GraphNode>getArgument(0).getNodeId(), FundPool.ofCodes(List.of("a", "b"), "ok")));
        when(analyzer.execute(any(), any(), any())).thenAnswer(i -> Artifact.of("research", ArtifactType.FUND_RESEARCH,
                i.<com.financial.copilot.agent.core.dag.model.GraphNode>getArgument(0).getNodeId(), FundResearchResult.ofBatch(List.of(), List.of("a", "b"))));
        when(comparator.execute(any(), any(), any())).thenAnswer(i -> Artifact.of("comparison", ArtifactType.COMPARISON_REPORT,
                i.<com.financial.copilot.agent.core.dag.model.GraphNode>getArgument(0).getNodeId(), ComparisonReport.of("a", "b", "facts", List.of())));
        when(synthesizer.execute(any(), any(), any())).thenAnswer(i -> Artifact.of("report", ArtifactType.FINAL_REPORT,
                i.<com.financial.copilot.agent.core.dag.model.GraphNode>getArgument(0).getNodeId(), FinalSynthesisReport.of("summary", "# report")));
        FinancialResearchWorkflow workflow = new FinancialResearchWorkflow(screener, analyzer, comparator, synthesizer,
                null, null, null, new GraphPlanner(), null, null, null, ReplanPolicy.never(), null);
        GraphRunRequest request = new GraphRunRequest("run", 7L, "session", "分析基金", false, null,
                ignored -> {}, RunMode.STREAM);

        GraphRunHandle handle = workflow.run(request);
        List<ResearchStreamEvent> events = handle.events().collectList().block();
        GraphRunResult result = handle.completion().get(2, TimeUnit.SECONDS);

        assertThat(result.artifacts().values()).anyMatch(a -> a.type() == ArtifactType.FINAL_REPORT);
        assertThat(events).extracting(ResearchStreamEvent::getType)
                .contains("graph_initialized", "node_ready", "node_started", "node_completed", "run_completed");
    }
}
