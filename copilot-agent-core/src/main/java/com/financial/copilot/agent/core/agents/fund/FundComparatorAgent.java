package com.financial.copilot.agent.core.agents.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.agentscope.AgentScopeInvocation;
import com.financial.copilot.agent.core.dag.artifact.*;
import com.financial.copilot.agent.core.dag.artifact.payload.ComparisonReport;
import com.financial.copilot.agent.core.dag.artifact.payload.FundPool;
import com.financial.copilot.agent.core.dag.artifact.payload.FundResearchResult;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.prompt.FundComparatorPrompt;
import com.financial.copilot.agent.core.workspace.ConfiguredToolWorkspacePublisher;
import com.financial.copilot.agent.tools.facade.FundComparisonToolSet;
import com.financial.copilot.agent.tools.model.ToolExecuteResult;
import com.financial.copilot.agent.tools.runtime.ConfiguredToolExecutionCollector;
import io.agentscope.core.tool.Toolkit;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.*;

/** AgentScope ReAct role for fund comparison and deep dives using configured HTTP tools. */
@Component
public class FundComparatorAgent {
    private final AgentScopeAgentFactory factory;
    private final FundComparisonToolSet fundComparisonToolSet;
    private final ConfiguredToolWorkspacePublisher workspacePublisher;
    private final ObjectMapper mapper;

    public FundComparatorAgent(AgentScopeAgentFactory factory,
                               @Nullable FundComparisonToolSet fundComparisonToolSet,
                               @Nullable ConfiguredToolWorkspacePublisher workspacePublisher,
                               ObjectMapper mapper) {
        this.factory = factory;
        this.fundComparisonToolSet = fundComparisonToolSet;
        this.workspacePublisher = workspacePublisher;
        this.mapper = mapper;
    }

    public Artifact<ComparisonReport> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        List<String> codes = codes(input, node);
        Toolkit toolkit = new Toolkit();
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
            if (workspacePublisher != null && !toolResults.isEmpty()) {
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

        String text = run.reply() != null ? run.reply().getTextContent() : "";
        ComparisonReport report = parseReport(text, codes);

        List<String> evidenceUris = new ArrayList<>(codes.stream().map(code -> "fund://" + code).toList());
        workspaceArtifactIds.forEach(id -> evidenceUris.add("artifact://" + id));

        return new Artifact<>("art-fund-compare-" + UUID.randomUUID().toString().substring(0, 8),
                node.getOutputType(), node.getNodeId(), report,
                ArtifactMetadata.standard("FundComparatorAgent"),
                EvidenceContract.sufficient("AgentScope 对标完成", evidenceUris));
    }

    private ComparisonReport parseReport(String text, List<String> codes) {
        try {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return mapper.readValue(text.substring(start, end + 1), ComparisonReport.class);
            }
        } catch (Exception ignored) {}
        return new ComparisonReport(codes, codes.isEmpty() ? null : codes.get(0), Map.of(), text);
    }

    private static List<String> codes(NodeInput input, GraphNode node) {
        if (node.getParams().get("targetCode") != null) {
            return List.of(String.valueOf(node.getParams().get("targetCode")));
        }
        for (Artifact<?> a : input.artifacts().values()) {
            if (a.payload() instanceof FundResearchResult res && res.topCandidates() != null && !res.topCandidates().isEmpty()) {
                return res.topCandidates().stream().distinct().limit(2).toList();
            }
        }
        for (Artifact<?> a : input.artifacts().values()) {
            if (a.payload() instanceof FundPool pool && !pool.fundCodes().isEmpty()) {
                return pool.fundCodes().stream().distinct().limit(2).toList();
            }
        }
        Object p = node.getParams().get("fundCodes");
        if (p instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        throw new IllegalStateException("FundComparatorAgent requires candidate codes");
    }
}
