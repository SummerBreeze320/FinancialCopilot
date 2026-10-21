package com.financial.copilot.agent.core.infra.agent;

import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.financial.copilot.agent.core.infra.coordination.AgentOperationLedger;
import com.financial.copilot.agent.core.infra.coordination.EvidenceMergeCoordinator;
import com.financial.copilot.agent.core.infra.tool.ResearchEvidenceTools;
import com.financial.copilot.agent.core.infra.tool.fund.FundResearchTools;
import com.financial.copilot.agent.core.infra.tool.market.MarketResearchTools;
import com.financial.copilot.agent.core.infra.tool.stock.StockResearchTools;
import com.financial.copilot.agent.core.infra.llm.execution.LlmCapacityLimiter;
import com.financial.copilot.agent.core.infra.admission.RuntimeCapacityProperties;
import com.financial.copilot.agent.core.infra.llm.ChatModelDelegateFactory;
import com.financial.copilot.agent.core.infra.llm.GovernedChatModel;
import com.financial.copilot.agent.core.infra.prompt.SubagentPrompts;
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
 * 遵循官方 Spring AI Alibaba 多智能体标准：
 * 1. 组装 5 个不可变的专业领域 Subagent（基金、股票、市场、风控审查、研报撰写）；
 * 2. 使用官方 {@link AgentTool#getFunctionToolCallback(ReactAgent)} 将专业 Subagent 挂载为 Tool；
 * 3. 通过 {@link ContextualAgentToolCallback} 注入隐式上下文与并发幂等保护；
 * 4. 构建中心调度总控 {@code researchSupervisor}。
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
            @Autowired(required = false) RuntimeCapacityProperties capacityProperties
    ) {
        int concurrency = capacityProperties != null ? capacityProperties.getMaxLlmConcurrency() : 16;
        return new GovernedChatModel(delegateFactory, new LlmCapacityLimiter(concurrency));
    }


    @Bean(name = "fundResearchAgent")
    public ReactAgent fundResearchAgent(
            @Qualifier("governedChatModel") ChatModel governedChatModel,
            FundResearchTools fundResearchTools
    ) {
        return ReactAgent.builder()
                .name("fund_research_agent")
                .description("负责公募基金档案查询、量化指标体检（收益/回撤/夏普/卡玛）、多维筛选与持仓穿透")
                .systemPrompt(SubagentPrompts.FUND_AGENT_SYSTEM_PROMPT)
                .model(governedChatModel)
                .methodTools(fundResearchTools)
                .saver(new MemorySaver())
                .build();
    }

    @Bean(name = "stockResearchAgent")
    public ReactAgent stockResearchAgent(
            @Qualifier("governedChatModel") ChatModel governedChatModel,
            StockResearchTools stockResearchTools
    ) {
        return ReactAgent.builder()
                .name("stock_research_agent")
                .description("负责 A 股股票全维档案、基本面财务指标（ROE、PE、PB、股息率）与量化分析")
                .systemPrompt(SubagentPrompts.STOCK_AGENT_SYSTEM_PROMPT)
                .model(governedChatModel)
                .methodTools(stockResearchTools)
                .saver(new MemorySaver())
                .build();
    }

    @Bean(name = "marketResearchAgent")
    public ReactAgent marketResearchAgent(
            @Qualifier("governedChatModel") ChatModel governedChatModel,
            MarketResearchTools marketResearchTools
    ) {
        return ReactAgent.builder()
                .name("market_research_agent")
                .description("负责金融指标与板块知识库混合召回、指标口径释义、知识图谱重合持仓对比与股票穿透")
                .systemPrompt(SubagentPrompts.MARKET_AGENT_SYSTEM_PROMPT)
                .model(governedChatModel)
                .methodTools(marketResearchTools)
                .saver(new MemorySaver())
                .build();
    }

    @Bean(name = "riskReviewAgent")
    public ReactAgent riskReviewAgent(
            @Qualifier("governedChatModel") ChatModel governedChatModel,
            ResearchEvidenceTools evidenceTools
    ) {
        return ReactAgent.builder()
                .name("risk_review_agent")
                .description("负责只读审查已生成的证据完整性、事实冲突、投资人适格匹配与风险合规披露")
                .systemPrompt(SubagentPrompts.RISK_REVIEW_SYSTEM_PROMPT)
                .model(governedChatModel)
                .methodTools(evidenceTools)
                .saver(new MemorySaver())
                .build();
    }

    @Bean(name = "reportAgent")
    public ReactAgent reportAgent(
            @Qualifier("governedChatModel") ChatModel governedChatModel,
            ResearchEvidenceTools evidenceTools
    ) {
        return ReactAgent.builder()
                .name("report_agent")
                .description("负责将通过合规复核的结构化事实证据合成排版精美、结构严谨的最终金融投研报告")
                .systemPrompt(SubagentPrompts.REPORT_AGENT_SYSTEM_PROMPT)
                .model(governedChatModel)
                .methodTools(evidenceTools)
                .saver(new MemorySaver())
                .build();
    }

    @Bean(name = "researchSupervisorAgent")
    public ReactAgent researchSupervisorAgent(
            @Qualifier("governedChatModel") ChatModel governedChatModel,
            @Qualifier("fundResearchAgent") ReactAgent fundAgent,
            @Qualifier("stockResearchAgent") ReactAgent stockAgent,
            @Qualifier("marketResearchAgent") ReactAgent marketAgent,
            @Qualifier("riskReviewAgent") ReactAgent riskAgent,
            @Qualifier("reportAgent") ReactAgent reportAgent,
            AgentOperationLedger operationLedger,
            EvidenceMergeCoordinator evidenceCoordinator,
            @Autowired(required = false) com.financial.copilot.agent.core.infra.guardrail.ActionGuardrail actionGuardrail
    ) {
        ToolCallback fundTool = new ContextualAgentToolCallback(
                AgentTool.getFunctionToolCallback(fundAgent),
                AgentRole.FUND_RESEARCH,
                operationLedger,
                evidenceCoordinator,
                actionGuardrail
        );
        ToolCallback stockTool = new ContextualAgentToolCallback(
                AgentTool.getFunctionToolCallback(stockAgent),
                AgentRole.STOCK_RESEARCH,
                operationLedger,
                evidenceCoordinator,
                actionGuardrail
        );
        ToolCallback marketTool = new ContextualAgentToolCallback(
                AgentTool.getFunctionToolCallback(marketAgent),
                AgentRole.MARKET_RESEARCH,
                operationLedger,
                evidenceCoordinator,
                actionGuardrail
        );
        ToolCallback riskTool = new ContextualAgentToolCallback(
                AgentTool.getFunctionToolCallback(riskAgent),
                AgentRole.RISK_REVIEW,
                operationLedger,
                evidenceCoordinator,
                actionGuardrail
        );
        ToolCallback reportTool = new ContextualAgentToolCallback(
                AgentTool.getFunctionToolCallback(reportAgent),
                AgentRole.REPORT,
                operationLedger,
                evidenceCoordinator,
                actionGuardrail
        );

        return ReactAgent.builder()
                .name("research_supervisor")
                .description("金融投研中心调度决策主管")
                .systemPrompt(SupervisorPrompts.SYSTEM_PROMPT)
                .model(governedChatModel)
                .tools(fundTool, stockTool, marketTool, riskTool, reportTool)
                .saver(new MemorySaver())
                .build();
    }
}
