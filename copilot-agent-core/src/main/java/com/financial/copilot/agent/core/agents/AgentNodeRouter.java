package com.financial.copilot.agent.core.agents;

import com.financial.copilot.agent.core.agents.fund.*;
import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.dag.runtime.NodeInput;
import org.springframework.stereotype.Component;

/** Plain DAG-to-role router for Fund research roles; it contains no model or agent loop. */
@Component
public class AgentNodeRouter {
    private final FundScreenerAgent fundScreener;
    private final FundAnalyzerAgent fundAnalyzer;
    private final FundComparatorAgent fundComparator;
    private final ReportSynthesizerAgent synthesizer;

    public AgentNodeRouter(FundScreenerAgent fundScreener,
                           FundAnalyzerAgent fundAnalyzer,
                           FundComparatorAgent fundComparator,
                           ReportSynthesizerAgent synthesizer) {
        this.fundScreener = fundScreener;
        this.fundAnalyzer = fundAnalyzer;
        this.fundComparator = fundComparator;
        this.synthesizer = synthesizer;
    }

    public Artifact<?> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        if (!AgentRoleCatalog.supports(node.getTaskType())) {
            throw new IllegalArgumentException("No AgentScope role for task type: " + node.getTaskType());
        }
        return switch (node.getTaskType()) {
            case "SCREENING" -> fundScreener.execute(node, input, context);
            case "BATCH_ANALYSIS" -> fundAnalyzer.execute(node, input, context);
            case "COMPARISON", "DEEP_DIVE" -> fundComparator.execute(node, input, context);
            case "SYNTHESIS" -> synthesizer.execute(node, input, context);
            default -> throw new IllegalStateException("Agent role catalog and router are inconsistent");
        };
    }
}

