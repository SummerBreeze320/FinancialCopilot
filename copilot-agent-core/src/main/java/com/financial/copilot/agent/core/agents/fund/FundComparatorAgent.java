package com.financial.copilot.agent.core.agents.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.agentscope.AgentScopeInvocation;
import com.financial.copilot.agent.core.dag.artifact.*;
import com.financial.copilot.agent.core.dag.artifact.payload.ComparisonReport;
import com.financial.copilot.agent.core.dag.artifact.payload.FundResearchResult;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.prompt.FundComparatorPrompt;
import com.financial.copilot.agent.core.workspace.ConfiguredToolWorkspacePublisher;
import com.financial.copilot.agent.tools.configured.facade.FundComparisonToolSet;
import com.financial.copilot.agent.tools.configured.model.ToolExecuteResult;
import com.financial.copilot.agent.tools.configured.runtime.ConfiguredToolExecutionCollector;
import com.financial.copilot.agent.tools.fund.*;
import io.agentscope.core.tool.*;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.*;

/** AgentScope ReAct role for fund comparison and deep dives. */
@Component
public class FundComparatorAgent {
    private final AgentScopeAgentFactory factory; private final FundQuantAnalysisTool quant;
    private final FundHoldingsQueryTool holdings; private final FundReportRetrieverTool reports;
    private final FundComparisonToolSet fundComparisonToolSet;
    private final ConfiguredToolWorkspacePublisher workspacePublisher;
    private final ObjectMapper mapper;

    public FundComparatorAgent(AgentScopeAgentFactory factory, FundQuantAnalysisTool quant,
                               FundHoldingsQueryTool holdings, FundReportRetrieverTool reports,
                               @Nullable FundComparisonToolSet fundComparisonToolSet,
                               @Nullable ConfiguredToolWorkspacePublisher workspacePublisher,
                               ObjectMapper mapper) {
        this.factory=factory; this.quant=quant; this.holdings=holdings; this.reports=reports;
        this.fundComparisonToolSet=fundComparisonToolSet; this.workspacePublisher=workspacePublisher;
        this.mapper=mapper;
    }

    public Artifact<ComparisonReport> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        List<String> codes = codes(input, node);
        Toolkit toolkit = new Toolkit(); toolkit.registerTool(new ComparisonTools(quant, holdings, reports));
        if (fundComparisonToolSet != null) {
            toolkit.registerTool(fundComparisonToolSet);
        }
        String userPrompt = FundComparatorPrompt.buildSpec(
                context.request().prompt(), codes, context.request().profile()).renderUserPrompt();

        ConfiguredToolExecutionCollector.clear();
        AgentScopeInvocation run;
        List<String> workspaceArtifactIds = List.of();
        try {
            run = factory.invokeWithTrace(new AgentScopeAgentFactory.AgentDefinition(
                    "FundComparatorAgent", "基金横向对标", FundComparatorPrompt.SYSTEM_PROMPT, toolkit, 8),
                    userPrompt, context);
        } finally {
            List<ToolExecuteResult> toolResults = ConfiguredToolExecutionCollector.drain();
            if (workspacePublisher != null) {
                workspaceArtifactIds = workspacePublisher.publish(context, toolResults);
            }
        }

        boolean hasLegacyMetrics = run.observations().containsKey("compare_metrics");
        boolean hasWorkspaceEvidence = !workspaceArtifactIds.isEmpty();
        boolean hasConfiguredObservations = run.observations().keySet().stream()
                .anyMatch(key -> key.startsWith("compare_"));
        if (!hasLegacyMetrics && !hasWorkspaceEvidence && !hasConfiguredObservations) {
            throw new IllegalStateException("FundComparatorAgent finished without comparison evidence");
        }

        String codeA = codes.getFirst(); String codeB = codes.size() > 1 ? codes.get(1) : codeA;
        List<String> shared = parseShared(run.observations().containsKey("shared_holdings")
                ? run.requireLastText("shared_holdings") : "{}");
        ComparisonReport payload = ComparisonReport.of(codeA, codeA.equals(codeB) ? "" : codeB,
                run.reply().getTextContent(), shared);

        List<String> evidenceUris = new ArrayList<>(codes.stream().map(code -> "fund://" + code).toList());
        workspaceArtifactIds.forEach(id -> evidenceUris.add("artifact://" + id));

        return new Artifact<>("art-comp-" + UUID.randomUUID().toString().substring(0,8),
                ArtifactType.COMPARISON_REPORT, node.getNodeId(), payload,
                ArtifactMetadata.standard("FundComparatorAgent"), EvidenceContract.sufficient(
                "AgentScope 对标工具已执行", evidenceUris));
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

    static final class ComparisonTools {
        private final FundQuantAnalysisTool q; private final FundHoldingsQueryTool h;
        private final FundReportRetrieverTool r;
        ComparisonTools(FundQuantAnalysisTool q, FundHoldingsQueryTool h, FundReportRetrieverTool r){this.q=q;this.h=h;this.r=r;}
        @Tool(name="compare_metrics", description="对称查询两只基金量化指标；单标的时两个代码相同", readOnly=true)
        public String metrics(@ToolParam(name="code_a",description="基金A") String a,@ToolParam(name="code_b",description="基金B") String b){return "A="+q.getFundMetrics(a,null,null)+"\nB="+q.getFundMetrics(b,null,null);}
        @Tool(name="compare_holdings", description="对称查询两只基金持仓", readOnly=true)
        public String holdings(@ToolParam(name="code_a",description="基金A") String a,@ToolParam(name="code_b",description="基金B") String b){return "A="+h.getTopHoldings(a,null)+"\nB="+h.getTopHoldings(b,null);}
        @Tool(name="compare_reports", description="对称查询两只基金季报", readOnly=true)
        public String reports(@ToolParam(name="code_a",description="基金A") String a,@ToolParam(name="code_b",description="基金B") String b){return "A="+r.getLatestQuarterlyReportView(a)+"\nB="+r.getLatestQuarterlyReportView(b);}
    }
}
