package com.financial.copilot.agent.core.infra.supervisor.agent;

import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.financial.copilot.agent.core.infra.supervisor.coordination.AgentOperationLedger;
import com.financial.copilot.agent.core.infra.supervisor.coordination.EvidenceMergeCoordinator;
import com.financial.copilot.agent.core.infra.supervisor.tool.ResearchEvidenceTools;
import com.financial.copilot.agent.core.infra.supervisor.tool.fund.FundResearchTools;
import com.financial.copilot.agent.core.infra.supervisor.tool.market.MarketResearchTools;
import com.financial.copilot.agent.core.infra.supervisor.tool.stock.StockResearchTools;
import com.financial.copilot.agent.core.infra.llm.execution.LlmCapacityLimiter;
import com.financial.copilot.agent.core.infra.supervisor.admission.RuntimeCapacityProperties;
import com.financial.copilot.agent.core.infra.supervisor.llm.ChatModelDelegateFactory;
import com.financial.copilot.agent.core.infra.supervisor.llm.GovernedChatModel;
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
                .systemPrompt("你是顶尖公募基金投研分析师。必须基于工具提供的真实数据回答，严禁心算或捏造量化指标。")
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
                .systemPrompt("你是顶尖 A 股股票投研分析师。必须基于工具提供的真实财务与量化数据进行分析，严禁主观编造。")
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
                .systemPrompt("你是金融市场宏观与知识图谱专家。负责指标释义、板块穿透与图谱交叉关联检索。")
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
                .systemPrompt("""
                        你是资深合规与风控总监 (risk_review_agent)。
                        职责约束：
                        1. 调用 getCollectedEvidences 获取当前全链路收集的真实事实证据，调用 getUserInvestmentProfile 获取投资者适格性画像；
                        2. 严格核验数据间是否存在事实冲突（如收益与回撤不匹配、估值矛盾等）；
                        3. 依据投资人风险评级（C1-C5）与适当性原则进行合规穿透，指出是否存在超风险推荐；
                        4. 输出专业合规审查结论与风险揭示。
                        """)
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
                .systemPrompt("""
                        你是机构级投资研报主笔与合成专家 (report_agent)。
                        职责约束：
                        1. 调用 getCollectedEvidences 获取已收集的权威事实证据，调用 getUserInvestmentProfile 获取投资者适格性画像；
                        2. 严格基于真实事实证据撰写专业、逻辑严谨、排版精美的 Markdown 机构级投研报告；
                        3. 研报架构：核心摘要 ➡️ 宏观与板块透视 ➡️ 核心标的量化体检 ➡️ 多标的横向对比 ➡️ 适格性资产配置建议 ➡️ 合规风险揭示；
                        4. 严禁编造任何未被证据库支持的数值。
                        """)
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
            EvidenceMergeCoordinator evidenceCoordinator
    ) {
        ToolCallback fundTool = new ContextualAgentToolCallback(
                AgentTool.getFunctionToolCallback(fundAgent),
                AgentRole.FUND_RESEARCH,
                operationLedger,
                evidenceCoordinator
        );
        ToolCallback stockTool = new ContextualAgentToolCallback(
                AgentTool.getFunctionToolCallback(stockAgent),
                AgentRole.STOCK_RESEARCH,
                operationLedger,
                evidenceCoordinator
        );
        ToolCallback marketTool = new ContextualAgentToolCallback(
                AgentTool.getFunctionToolCallback(marketAgent),
                AgentRole.MARKET_RESEARCH,
                operationLedger,
                evidenceCoordinator
        );
        ToolCallback riskTool = new ContextualAgentToolCallback(
                AgentTool.getFunctionToolCallback(riskAgent),
                AgentRole.RISK_REVIEW,
                operationLedger,
                evidenceCoordinator
        );
        ToolCallback reportTool = new ContextualAgentToolCallback(
                AgentTool.getFunctionToolCallback(reportAgent),
                AgentRole.REPORT,
                operationLedger,
                evidenceCoordinator
        );

        return ReactAgent.builder()
                .name("research_supervisor")
                .description("金融投研中心调度决策主管")
                .systemPrompt("""
                        你是金融投研平台的总控调度决策智能体（Supervisor）。
                        你的职责是：
                        1. 深度理解用户的投资研究诉求；
                        2. 准确委派专业子智能体（fund_research_agent、stock_research_agent、market_research_agent）获取权威数据事实；
                        3. 在最终回答前调用 risk_review_agent 审查数据冲突与合规要点；
                        4. 针对复杂研报诉求，委派 report_agent 生成标准报告；
                        5. 给出专业客观的最终答复。
                        """)
                .model(governedChatModel)
                .tools(fundTool, stockTool, marketTool, riskTool, reportTool)
                .saver(new MemorySaver())
                .build();
    }
}
