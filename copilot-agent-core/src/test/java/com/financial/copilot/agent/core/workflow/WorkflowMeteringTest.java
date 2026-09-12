package com.financial.copilot.agent.core.workflow;

import com.financial.copilot.agent.core.agents.*;
import com.financial.copilot.agent.core.dag.artifact.*;
import com.financial.copilot.agent.core.dag.artifact.payload.*;
import com.financial.copilot.agent.core.dag.planner.GraphPlanner;
import com.financial.copilot.agent.core.dag.runtime.*;
import com.financial.copilot.agent.core.llm.dto.LlmResponse;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowMeteringTest {
    @Test
    void nodeContextReceivesOriginalMeter() throws Exception {
        ScreenerAgent screener = mock(ScreenerAgent.class);
        AnalyzerAgent analyzer = mock(AnalyzerAgent.class);
        ComparatorAgent comparator = mock(ComparatorAgent.class);
        ReportSynthesizer synthesizer = mock(ReportSynthesizer.class);
        when(screener.execute(any(), any(), any())).thenAnswer(i -> Artifact.of("p", ArtifactType.FUND_POOL, "step-1-screening", FundPool.ofCodes(List.of("a", "b"), "")));
        when(analyzer.execute(any(), any(), any())).thenAnswer(i -> Artifact.of("a", ArtifactType.FUND_RESEARCH, "step-2-analysis", FundResearchResult.ofBatch(List.of(), List.of("a", "b"))));
        when(comparator.execute(any(), any(), any())).thenAnswer(i -> Artifact.of("c", ArtifactType.COMPARISON_REPORT, "step-3-comparison", ComparisonReport.of("a", "b", "", List.of())));
        when(synthesizer.execute(any(), any(), any())).thenAnswer(i -> Artifact.of("r", ArtifactType.FINAL_REPORT, "step-4-synthesis", FinalSynthesisReport.of("", "report")));
        FinancialResearchWorkflow workflow = new FinancialResearchWorkflow(screener, analyzer, comparator, synthesizer,
                null, null, null, new GraphPlanner(), null, null, null, ReplanPolicy.never(), null);
        Consumer<LlmResponse> meter = ignored -> {};

        workflow.run(new GraphRunRequest("run", 7L, "s", "分析基金", false, null, meter, RunMode.SYNC))
                .completion().get();

        verify(comparator).execute(any(), any(), argThat(context -> context.request().usageConsumer() == meter));
        verify(synthesizer).execute(any(), any(), argThat(context -> context.request().usageConsumer() == meter));
    }
}
