package com.financial.copilot.agent.core.business.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.agentscope.RoleToolkitRegistry;
import com.financial.copilot.agent.core.infra.dag.artifact.*;
import com.financial.copilot.agent.core.infra.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.infra.supervisor.ReviewAction;
import com.financial.copilot.agent.core.infra.supervisor.ReviewDecision;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * <h1>研报终审与合成聚合专家 (ReportSynthesizerAgent / AggregatorAgent)</h1>
 * <p>
 * 遵循 AgentScope ReAct 范式，通过 read_research_artifacts 读取全链路事实产物。
 * 支持根据 ReviewDecision 注入数据缺口风险揭示与降级摘要披露。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class ReportSynthesizerAgent implements AggregatorAgent {

    private final AgentScopeAgentFactory factory;
    private final RoleToolkitRegistry toolkitRegistry;

    @Autowired
    public ReportSynthesizerAgent(AgentScopeAgentFactory factory, RoleToolkitRegistry toolkitRegistry) {
        this.factory = factory;
        this.toolkitRegistry = toolkitRegistry;
    }

    public ReportSynthesizerAgent(AgentScopeAgentFactory factory, ObjectMapper mapper) {
        this(factory, RoleToolkitRegistry.forReportSynthesizer(mapper));
    }

    @Override
    public Artifact<FinalSynthesisReport> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        return executeWithReview(node, input, context, null);
    }

    @Override
    public Artifact<FinalSynthesisReport> executeWithReview(GraphNode node, NodeInput input, NodeExecutionContext context, ReviewDecision reviewDecision) {
        NodeExecutionContext effectiveContext = (context.input() != null || input == null) ? context
                : new NodeExecutionContext(context.request(), context.nodeId(), context.artifacts(), context.cancellationToken(), context.attempt(), input);
        Toolkit toolkit = toolkitRegistry.createToolkit(AgentRole.REPORT_SYNTHESIZER, effectiveContext);
        if (toolkit == null) {
            toolkit = toolkitRegistry.toolkitFor(AgentRole.REPORT_SYNTHESIZER);
        }

        String baseUserPrompt = com.financial.copilot.agent.core.infra.prompt.ReportSynthesizerPrompt.buildSpec(
                context.request().prompt(), context.request().profile()).renderUserPrompt();
        String effectivePrompt = baseUserPrompt;
        boolean isDegraded = reviewDecision != null && reviewDecision.action() == ReviewAction.DEGRADE;
        if (isDegraded) {
            effectivePrompt = "【注意：本次研究存在数据缺口并触发降级处理】\n"
                    + "已识别问题/限制：" + reviewDecision.issues() + "\n"
                    + "建议补充但缺失的证据：" + reviewDecision.requiredEvidence() + "\n"
                    + "请在研报开头摘要与正文中明确提示该限制与降级披露，切勿生成未经证实的推论！\n\n"
                    + baseUserPrompt;
        }

        var run = factory.invokeWithTrace(new AgentScopeAgentFactory.AgentDefinition("ReportSynthesizerAgent", "投研报告终审",
                com.financial.copilot.agent.core.infra.prompt.ReportSynthesizerPrompt.SYSTEM_PROMPT, toolkit, 5),
                effectivePrompt, effectiveContext);
        run.requireLastText("read_research_artifacts");

        List<String> evidence = input != null && input.artifacts() != null
                ? input.artifacts().values().stream().map(Artifact::evidenceContract)
                .filter(Objects::nonNull).flatMap(contract -> contract.evidenceUris().stream()).distinct().toList()
                : List.of();
        List<String> funds = evidence.stream().filter(uri -> uri.startsWith("fund://")).map(uri -> uri.substring(7)).toList();

        String summary = isDegraded
                ? "【降级披露】AgentScope ReAct 基于部分已验证证据合成（存在数据缺口: " + reviewDecision.issues() + "）"
                : "AgentScope ReAct 基于全链路工具证据合成";

        FinalSynthesisReport payload = FinalSynthesisReport.of("专业投资研究报告", summary,
                run.reply().getTextContent(), Map.of(), funds);
        return new Artifact<>("art-report-" + UUID.randomUUID().toString().substring(0, 8), ArtifactType.FINAL_REPORT,
                node.getNodeId(), payload, ArtifactMetadata.standard("ReportSynthesizerAgent"),
                EvidenceContract.sufficient("报告已引用绑定产物", evidence));
    }
}
