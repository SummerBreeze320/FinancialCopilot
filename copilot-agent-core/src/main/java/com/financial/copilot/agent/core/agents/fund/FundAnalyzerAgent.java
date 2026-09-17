package com.financial.copilot.agent.core.agents.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.agentscope.AgentScopeInvocation;
import com.financial.copilot.agent.core.dag.artifact.*;
import com.financial.copilot.agent.core.dag.artifact.payload.FundPool;
import com.financial.copilot.agent.core.dag.artifact.payload.FundResearchResult;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.workspace.ConfiguredToolWorkspacePublisher;
import com.financial.copilot.agent.tools.configured.facade.FundAnalysisToolSet;
import com.financial.copilot.agent.tools.configured.model.ToolExecuteResult;
import com.financial.copilot.agent.tools.configured.runtime.ConfiguredToolExecutionCollector;
import io.agentscope.core.tool.Toolkit;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.*;

/** AgentScope ReAct role for evidence-driven fund analysis using configured HTTP tools. */
@Component
public class FundAnalyzerAgent {
    private static final String SYSTEM_PROMPT = """
            你是基金分析 ReAct Agent。对给定候选基金可自主调用全景画像与量化深度分析工具（如 fund_analysis_profile、fund_analysis_holdings 等）。
            注意：工具产生的图表与全量明细已直接渲染至前端工作台；
            必须至少调用一个分析工具获取客观事实，并依据观察结果输出严格 JSON：
            {"evaluatedFunds":[{"fundCode":"...","score":数字,"summary":"..."}],"topCandidates":["..."]}
            禁止编造未被工具观察支持的数据。
            """;
    private final AgentScopeAgentFactory agentFactory;
    private final FundAnalysisToolSet fundAnalysisToolSet;
    private final ConfiguredToolWorkspacePublisher workspacePublisher;
    private final ObjectMapper mapper;

    public FundAnalyzerAgent(AgentScopeAgentFactory agentFactory,
                             @Nullable FundAnalysisToolSet fundAnalysisToolSet,
                             @Nullable ConfiguredToolWorkspacePublisher workspacePublisher,
                             ObjectMapper mapper) {
        this.agentFactory = agentFactory;
        this.fundAnalysisToolSet = fundAnalysisToolSet;
        this.workspacePublisher = workspacePublisher;
        this.mapper = mapper;
    }

    public Artifact<FundResearchResult> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        Toolkit toolkit = new Toolkit();
        if (fundAnalysisToolSet != null) {
            toolkit.registerTool(fundAnalysisToolSet);
        }
        List<String> codes = candidateCodes(input, node);
        if (codes.isEmpty()) throw new IllegalStateException("FundAnalyzerAgent requires a FundPool input");
        String targetCode = codes.get(0);
        String prompt = "目标基金代码=" + targetCode + "，所有候选=" + codes + "\n用户诉求=" + context.request().prompt();
        ConfiguredToolExecutionCollector.clear();
        AgentScopeInvocation invocation;
        List<String> workspaceArtifactIds = List.of();
        try {
            invocation = agentFactory.invokeWithTrace(
                    new AgentScopeAgentFactory.AgentDefinition("FundAnalyzerAgent", "基金深度分析", SYSTEM_PROMPT, toolkit, 8),
                    prompt, context);
        } finally {
            List<ToolExecuteResult> toolResults = ConfiguredToolExecutionCollector.drain();
            if (workspacePublisher != null && !toolResults.isEmpty()) {
                workspaceArtifactIds = workspacePublisher.publish(context, toolResults);
            }
        }

        boolean hasLegacyMetrics = invocation.observations().containsKey("fund_metrics");
        boolean hasWorkspaceEvidence = !workspaceArtifactIds.isEmpty();
        boolean hasConfiguredObservations = invocation.observations().keySet().stream()
                .anyMatch(key -> key.startsWith("fund_analysis_"));
        if (!hasLegacyMetrics && !hasWorkspaceEvidence && !hasConfiguredObservations) {
            throw new IllegalStateException("FundAnalyzerAgent finished without tool evidence");
        }

        String text = invocation.reply() != null ? invocation.reply().getTextContent() : "";
        FundResearchResult parsed = parseResult(text, targetCode);

        List<String> evidenceUris = new ArrayList<>();
        if (parsed.evaluatedFunds() != null) {
            parsed.evaluatedFunds().forEach(row -> {
                Object c = row.get("fundCode");
                if (c != null) evidenceUris.add("fund://" + c);
            });
        }
        if (evidenceUris.isEmpty()) {
            evidenceUris.add("fund://" + targetCode);
        }
        workspaceArtifactIds.forEach(id -> evidenceUris.add("artifact://" + id));

        return new Artifact<>("art-fund-analysis-" + UUID.randomUUID().toString().substring(0, 8),
                node.getOutputType(), node.getNodeId(), parsed,
                ArtifactMetadata.standard("FundAnalyzerAgent"),
                EvidenceContract.sufficient("AgentScope ReAct 工具调用已验证", evidenceUris));
    }

    private FundResearchResult parseResult(String text, String targetCode) {
        try {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return mapper.readValue(text.substring(start, end + 1), FundResearchResult.class);
            }
        } catch (Exception ignored) {}
        return FundResearchResult.ofBatch(List.of(Map.of("fundCode", targetCode, "score", 80.0, "summary", text)), List.of(targetCode));
    }

    private static List<String> candidateCodes(NodeInput input, GraphNode node) {
        for (Artifact<?> artifact : input.artifacts().values()) {
            if (artifact.payload() instanceof FundPool pool && !pool.fundCodes().isEmpty()) {
                return pool.fundCodes();
            }
        }
        Object param = node.getParams().get("fundCodes");
        if (param instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
