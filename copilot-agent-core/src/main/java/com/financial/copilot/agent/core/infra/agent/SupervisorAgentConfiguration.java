package com.financial.copilot.agent.core.infra.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.financial.copilot.agent.core.infra.admission.RuntimeCapacityProperties;
import com.financial.copilot.agent.core.infra.coordination.AgentOperationLedger;
import com.financial.copilot.agent.core.infra.coordination.EvidenceMergeCoordinator;
import com.financial.copilot.agent.core.infra.guardrail.ActionGuardrail;
import com.financial.copilot.agent.core.infra.llm.ChatModelDelegateFactory;
import com.financial.copilot.agent.core.infra.llm.GovernedChatModel;
import com.financial.copilot.agent.core.infra.llm.execution.LlmCapacityLimiter;
import com.financial.copilot.agent.core.infra.prompt.SupervisorPrompts;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * <h1>Supervisor 与专业 Subagent 拓扑配置中心 (SupervisorAgentConfiguration)</h1>
 * <p>
 * 遵循平台角色驱动 (Role-Driven) 统一架构与 Spring AI Alibaba 多智能体标准：
 * <ul>
 *     <li>由 {@link SubagentFactory} 统一部署与管理专业领域 Subagent；</li>
 *     <li>总控调度 Supervisor (researchSupervisorAgent) 通过工厂动态挂载全量角色工具回调；</li>
 *     <li>平滑暴露各领域角色代理 Bean 以保持向后兼容。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Configuration
public class SupervisorAgentConfiguration {

    @Bean(name = "governedChatModel")
    @org.springframework.context.annotation.Primary
    @ConditionalOnMissingBean(name = "governedChatModel")
    public ChatModel governedChatModel(
            ChatModelDelegateFactory delegateFactory,
            @Autowired(required = false) RuntimeCapacityProperties capacityProperties,
            @Autowired(required = false) com.financial.copilot.agent.core.infra.observability.AgentTracer agentTracer
    ) {
        int concurrency = capacityProperties != null ? capacityProperties.getMaxLlmConcurrency() : 16;
        return new GovernedChatModel(delegateFactory, new LlmCapacityLimiter(concurrency), agentTracer);
    }

    @Bean(name = "fundResearchAgent")
    public ReactAgent fundResearchAgent(SubagentFactory subagentFactory) {
        return subagentFactory.getSubagent(AgentRole.FUND_RESEARCH);
    }

    @Bean(name = "stockResearchAgent")
    public ReactAgent stockResearchAgent(SubagentFactory subagentFactory) {
        return subagentFactory.getSubagent(AgentRole.STOCK_RESEARCH);
    }

    @Bean(name = "marketResearchAgent")
    public ReactAgent marketResearchAgent(SubagentFactory subagentFactory) {
        return subagentFactory.getSubagent(AgentRole.MARKET_RESEARCH);
    }

    @Bean(name = "riskReviewAgent")
    public ReactAgent riskReviewAgent(SubagentFactory subagentFactory) {
        return subagentFactory.getSubagent(AgentRole.RISK_REVIEW);
    }

    @Bean(name = "reportAgent")
    public ReactAgent reportAgent(SubagentFactory subagentFactory) {
        return subagentFactory.getSubagent(AgentRole.REPORT);
    }

    @Bean(name = "researchSupervisorAgent")
    public ReactAgent researchSupervisorAgent(
            @Qualifier("governedChatModel") ChatModel governedChatModel,
            SubagentFactory subagentFactory,
            AgentOperationLedger operationLedger,
            EvidenceMergeCoordinator evidenceCoordinator,
            @Autowired(required = false) ActionGuardrail actionGuardrail
    ) {
        List<ToolCallback> subagentTools = subagentFactory.createAllSubagentToolCallbacks(
                operationLedger,
                evidenceCoordinator,
                actionGuardrail
        );

        return ReactAgent.builder()
                .name(AgentRole.SUPERVISOR.getAgentName())
                .description(AgentRole.SUPERVISOR.getDescription())
                .systemPrompt(SupervisorPrompts.SYSTEM_PROMPT)
                .model(governedChatModel)
                .tools(subagentTools.toArray(new ToolCallback[0]))
                .saver(new MemorySaver())
                .build();
    }
}

