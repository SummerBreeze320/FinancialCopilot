package com.financial.copilot.agent.core.business.agents.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.business.agents.AgentRole;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.agentscope.RoleToolkitRegistry;
import com.financial.copilot.agent.core.infra.dag.artifact.*;
import com.financial.copilot.agent.core.infra.dag.artifact.payload.FundPool;
import com.financial.copilot.agent.core.infra.dag.artifact.payload.FundResearchResult;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** AgentScope ReAct role for evidence-driven fund analysis. */
@Component
public class FundAnalyzerAgent {
    private static final String SYSTEM_PROMPT = """
            你是基金分析 ReAct Agent。对给定候选基金自主调用 fund_metrics、fund_holdings、fund_report。
            至少调用 fund_metrics，并依据观察结果输出严格 JSON：
            {"evaluatedFunds":[{"fundCode":"...","score":数字,"summary":"..."}],"topCandidates":["..."]}
            禁止编造未被工具观察支持的数据。
            """;
    private final AgentScopeAgentFactory agentFactory;
    private final RoleToolkitRegistry toolkitRegistry;
    private final ObjectMapper mapper;

    @Autowired
    public FundAnalyzerAgent(AgentScopeAgentFactory agentFactory, RoleToolkitRegistry toolkitRegistry, ObjectMapper mapper) {
        this.agentFactory = agentFactory;
        this.toolkitRegistry = toolkitRegistry;
        this.mapper = mapper;
    }

    public FundAnalyzerAgent(AgentScopeAgentFactory agentFactory, FundQuantAnalysisTool quantTool,
                             FundHoldingsQueryTool holdingsTool, FundReportRetrieverTool reportTool,
                             ObjectMapper mapper) {
        this(agentFactory, RoleToolkitRegistry.forFundAnalyzer(quantTool, holdingsTool, reportTool), mapper);
    }

    public Artifact<FundResearchResult> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        Toolkit toolkit = toolkitRegistry.toolkitFor(AgentRole.FUND_ANALYZER);
        List<String> codes = candidateCodes(input, node);
        if (codes.isEmpty()) throw new IllegalStateException("FundAnalyzerAgent requires a FundPool input");
        var invocation = agentFactory.invokeWithTrace(new AgentScopeAgentFactory.AgentDefinition(
                "FundAnalyzerAgent", "基金量化与定性分析", SYSTEM_PROMPT, toolkit, 8),
                "用户目标=" + context.request().prompt() + "\n候选基金=" + codes + "\n节点参数=" + node.getParams(), context);
        if (!invocation.observations().containsKey("fund_metrics"))
            throw new IllegalStateException("FundAnalyzerAgent finished without fund_metrics evidence");
        try {
            Decision decision = mapper.readValue(stripFence(invocation.reply().getTextContent()), Decision.class);
            if (decision.evaluatedFunds() == null || decision.evaluatedFunds().isEmpty())
                throw new IllegalArgumentException("evaluatedFunds is empty");
            List<String> top = decision.topCandidates() == null ? List.of() : decision.topCandidates();
            FundResearchResult payload = FundResearchResult.ofBatch(decision.evaluatedFunds(), top);
            return new Artifact<>("art-analysis-" + UUID.randomUUID().toString().substring(0, 8),
                    ArtifactType.FUND_RESEARCH, node.getNodeId(), payload,
                    ArtifactMetadata.standard("FundAnalyzerAgent"),
                    EvidenceContract.sufficient("AgentScope 工具证据分析完成",
                            decision.evaluatedFunds().stream().map(row -> "fund://" + row.get("fundCode")).toList()));
        } catch (Exception e) {
            throw new IllegalStateException("FundAnalyzerAgent returned invalid structured result", e);
        }
    }

    private List<String> candidateCodes(NodeInput input, GraphNode node) {
        return input.artifacts().values().stream().map(Artifact::payload).filter(FundPool.class::isInstance)
                .map(FundPool.class::cast).flatMap(pool -> pool.fundCodes().stream()).distinct()
                .limit(node.getParams().get("topN") instanceof Number n ? n.longValue() : 5).toList();
    }

    private static String stripFence(String text) {
        if (text == null) return "";
        return text.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
    }

    public record Decision(List<Map<String, Object>> evaluatedFunds, List<String> topCandidates) {}
}
