package com.financial.copilot.agent.core.business.agents;

import com.financial.copilot.agent.core.business.agents.fund.*;
import com.financial.copilot.agent.core.business.agents.stock.*;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import org.springframework.stereotype.Component;

/** Plain DAG-to-role router; it contains no model or agent loop. */
@Component
public class AgentNodeRouter {
    private final FundScreenerAgent fundScreener;
    private final StockScreenerAgent stockScreener;
    private final FundAnalyzerAgent fundAnalyzer;
    private final StockAnalyzerAgent stockAnalyzer;
    private final FundComparatorAgent fundComparator;
    private final StockComparatorAgent stockComparator;
    private final ReportSynthesizerAgent synthesizer;

    public AgentNodeRouter(FundScreenerAgent fundScreener, StockScreenerAgent stockScreener,
                           FundAnalyzerAgent fundAnalyzer, StockAnalyzerAgent stockAnalyzer,
                           FundComparatorAgent fundComparator, StockComparatorAgent stockComparator,
                           ReportSynthesizerAgent synthesizer) {
        this.fundScreener = fundScreener;
        this.stockScreener = stockScreener;
        this.fundAnalyzer = fundAnalyzer;
        this.stockAnalyzer = stockAnalyzer;
        this.fundComparator = fundComparator;
        this.stockComparator = stockComparator;
        this.synthesizer = synthesizer;
    }

    public Artifact<?> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        if (!AgentRoleCatalog.supports(node.getTaskType())) {
            throw new IllegalArgumentException("No AgentScope role for task type: " + node.getTaskType());
        }
        boolean stock = "STOCK".equalsIgnoreCase(String.valueOf(node.getParams().get("assetCategory")))
                || context.request().prompt().contains("股票") || context.request().prompt().contains("个股");
        return switch (node.getTaskType()) {
            case "SCREENING" -> stock ? stockScreener.execute(node, input, context) : fundScreener.execute(node, input, context);
            case "BATCH_ANALYSIS" -> stock ? stockAnalyzer.execute(node, input, context) : fundAnalyzer.execute(node, input, context);
            case "COMPARISON", "DEEP_DIVE" -> stock
                    ? stockComparator.execute(node, input, context)
                    : fundComparator.execute(node, input, context);
            case "SYNTHESIS" -> synthesizer.execute(node, input, context);
            default -> throw new IllegalStateException("Agent role catalog and router are inconsistent");
        };
    }
}
