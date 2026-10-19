package com.financial.copilot.agent.core.business.agents.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.business.agents.AgentRole;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.agentscope.RoleToolkitRegistry;
import com.financial.copilot.agent.core.infra.dag.artifact.*;
import com.financial.copilot.agent.core.infra.dag.artifact.payload.ComparisonReport;
import com.financial.copilot.agent.core.infra.dag.artifact.payload.FundResearchResult;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.infra.prompt.FundComparatorPrompt;
import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import com.financial.copilot.agent.tools.graph.FinancialGraphTool;
import com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent;
import com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions;
import io.a2a.spec.AgentCard;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** AgentScope ReAct role for fund comparison and deep dives. */
@Component
public class FundComparatorAgent implements A2aExecutableAgent {

    @Override
    public AgentCard agentCard() {
        return AgentCardDefinitions.fundComparatorCard();
    }

    private final AgentScopeAgentFactory factory;
    private final RoleToolkitRegistry toolkitRegistry;
    private final ObjectMapper mapper;

    @Autowired
    public FundComparatorAgent(AgentScopeAgentFactory factory,
                               RoleToolkitRegistry toolkitRegistry,
                               ObjectMapper mapper) {
        this.factory = factory;
        this.toolkitRegistry = toolkitRegistry;
        this.mapper = mapper;
    }

    public FundComparatorAgent(AgentScopeAgentFactory factory, FundQuantAnalysisTool quant,
                               FundHoldingsQueryTool holdings, FundReportRetrieverTool reports,
                               @Nullable FinancialGraphTool graph, ObjectMapper mapper) {
        this(factory, RoleToolkitRegistry.forFundComparator(quant, holdings, reports, graph), mapper);
    }

    public Artifact<ComparisonReport> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        List<String> codes = codes(input, node);
        Toolkit toolkit = toolkitRegistry.createToolkit(AgentRole.FUND_COMPARATOR, context);
        if (toolkit == null) {
            toolkit = toolkitRegistry.toolkitFor(AgentRole.FUND_COMPARATOR);
        }
        String userPrompt = FundComparatorPrompt.buildSpec(
                context.request().prompt(), codes, context.request().profile()).renderUserPrompt();
        var run = factory.invokeWithTrace(new AgentScopeAgentFactory.AgentDefinition(
                "FundComparatorAgent", "基金横向对标", FundComparatorPrompt.SYSTEM_PROMPT, toolkit, 8),
                userPrompt, context);
        run.requireLastText("compare_metrics");
        String codeA = codes.getFirst(); String codeB = codes.size() > 1 ? codes.get(1) : codeA;
        List<String> shared = parseShared(run.observations().containsKey("shared_holdings")
                ? run.requireLastText("shared_holdings") : "{}");
        ComparisonReport payload = ComparisonReport.of(codeA, codeA.equals(codeB) ? "" : codeB,
                run.reply().getTextContent(), shared);
        return new Artifact<>("art-comp-" + UUID.randomUUID().toString().substring(0,8),
                ArtifactType.COMPARISON_REPORT, node.getNodeId(), payload,
                ArtifactMetadata.standard("FundComparatorAgent"), EvidenceContract.sufficient(
                "AgentScope 对标工具已执行", codes.stream().map(code -> "fund://" + code).toList()));
    }

    private List<String> codes(NodeInput input, GraphNode node) {
        if (node.getParams().get("targetCode") != null) return List.of(String.valueOf(node.getParams().get("targetCode")));
        List<String> found = input.artifacts().values().stream().map(Artifact::payload)
                .filter(FundResearchResult.class::isInstance).map(FundResearchResult.class::cast)
                .flatMap(result -> result.topCandidates().stream()).distinct().limit(2).toList();
        if (found.isEmpty()) throw new IllegalStateException("FundComparatorAgent requires candidate codes");
        return found;
    }

    private List<String> parseShared(String json) {
        try {
            var array = mapper.readTree(json).path("sharedStockCodes");
            List<String> result = new ArrayList<>(); array.forEach(item -> result.add(item.asText())); return result;
        } catch (Exception ignored) { return List.of(); }
    }
}
