package com.financial.copilot.agent.core.business.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.agentscope.RoleToolkitRegistry;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.prompt.ReviewerPrompt;
import com.financial.copilot.agent.core.infra.supervisor.ReviewDecision;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * <h1>投研跨证据质量审查 Agent (ReviewerAgent)</h1>
 * <p>
 * 具备只读工具包，审查经 Validator 通过的产物一致性、覆盖度与风险，
 * 输出结构化 ReviewDecision (PASS, RETRY_TASK, REPLAN, DEGRADE)。
 * 不得调用写工具，亦不得直接合成最终研报。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class ReviewerAgent {

    private final AgentScopeAgentFactory factory;
    private final RoleToolkitRegistry toolkitRegistry;
    private final ObjectMapper mapper;

    @Autowired
    public ReviewerAgent(AgentScopeAgentFactory factory, RoleToolkitRegistry toolkitRegistry, ObjectMapper mapper) {
        this.factory = Objects.requireNonNull(factory, "factory cannot be null");
        this.toolkitRegistry = Objects.requireNonNull(toolkitRegistry, "toolkitRegistry cannot be null");
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public ReviewerAgent(AgentScopeAgentFactory factory, ObjectMapper mapper) {
        this(factory, RoleToolkitRegistry.forReviewer(mapper), mapper);
    }

    public ReviewDecision review(ExecutionGraph graph, ArtifactStore store, NodeExecutionContext context) {
        Toolkit toolkit = toolkitRegistry.createToolkit(AgentRole.REVIEWER, context);
        if (toolkit == null) {
            toolkit = toolkitRegistry.toolkitFor(AgentRole.REVIEWER);
        }
        String userPrompt = "审查当前已完成执行图产物质量，并给出 ReviewDecision。\n用户原始问题："
                + (context.request() != null ? context.request().prompt() : "") + "\n图节点状态：" + (graph != null ? graph.getNodes().keySet() : "[]");

        var run = factory.invokeWithTrace(new AgentScopeAgentFactory.AgentDefinition(
                "ReviewerAgent", "跨证据研报审查", ReviewerPrompt.SYSTEM_PROMPT, toolkit, 5),
                userPrompt, context);

        try {
            String text = stripFence(run.reply().getTextContent());
            return mapper.readValue(text, ReviewDecision.class);
        } catch (Exception e) {
            return ReviewDecision.degrade("Reviewer response parse failed: " + e.getMessage(), List.of("JSON parse error"));
        }
    }

    private static String stripFence(String text) {
        return text == null ? "" : text.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
    }
}
