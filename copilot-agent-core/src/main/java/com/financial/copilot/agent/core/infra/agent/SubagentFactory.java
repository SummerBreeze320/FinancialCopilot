package com.financial.copilot.agent.core.infra.agent;

import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.financial.copilot.agent.core.infra.coordination.AgentOperationLedger;
import com.financial.copilot.agent.core.infra.coordination.EvidenceMergeCoordinator;
import com.financial.copilot.agent.core.infra.guardrail.ActionGuardrail;
import com.financial.copilot.agent.core.infra.prompt.SubagentPrompts;
import com.financial.copilot.agent.core.infra.tool.ResearchEvidenceTools;
import com.financial.copilot.agent.core.infra.tool.fund.FundResearchTools;
import com.financial.copilot.agent.core.infra.tool.market.MarketResearchTools;
import com.financial.copilot.agent.core.infra.tool.stock.StockResearchTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>角色驱动专业子智能体工厂 (SubagentFactory)</h1>
 * <p>
 * 核心职责：
 * <ul>
 *     <li>消除硬编码的独立专向 Agent Bean 声明，基于 {@link AgentRole} 元数据统一构建与管理专业领域子智能体；</li>
 *     <li>根据 {@link AgentRole} 自动路由匹配的专业工具集（公募基金、A股股票、宏观知识图谱、风控核验与研报合成）；</li>
 *     <li>自动挂载统一的 {@link SubagentPrompts} 领域系统提示词与 {@link MemorySaver} 状态检查点；</li>
 *     <li>提供标准工具回调生成接口，一键输出封装了 {@link ContextualAgentToolCallback}、上下文隔离与动作守卫的 ToolCallback 列表。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class SubagentFactory {

    private final ChatModel chatModel;
    private final FundResearchTools fundResearchTools;
    private final StockResearchTools stockResearchTools;
    private final MarketResearchTools marketResearchTools;
    private final ResearchEvidenceTools evidenceTools;
    private final com.financial.copilot.agent.core.infra.observability.AgentTracer agentTracer;

    private final Map<AgentRole, ReactAgent> agentCache = new ConcurrentHashMap<>();

    public SubagentFactory(
            @Qualifier("governedChatModel") ChatModel chatModel,
            FundResearchTools fundResearchTools,
            StockResearchTools stockResearchTools,
            MarketResearchTools marketResearchTools,
            ResearchEvidenceTools evidenceTools
    ) {
        this(chatModel, fundResearchTools, stockResearchTools, marketResearchTools, evidenceTools, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SubagentFactory(
            @Qualifier("governedChatModel") ChatModel chatModel,
            FundResearchTools fundResearchTools,
            StockResearchTools stockResearchTools,
            MarketResearchTools marketResearchTools,
            ResearchEvidenceTools evidenceTools,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.financial.copilot.agent.core.infra.observability.AgentTracer agentTracer
    ) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.fundResearchTools = Objects.requireNonNull(fundResearchTools, "fundResearchTools cannot be null");
        this.stockResearchTools = Objects.requireNonNull(stockResearchTools, "stockResearchTools cannot be null");
        this.marketResearchTools = Objects.requireNonNull(marketResearchTools, "marketResearchTools cannot be null");
        this.evidenceTools = Objects.requireNonNull(evidenceTools, "evidenceTools cannot be null");
        this.agentTracer = agentTracer;
    }

    /**
     * 根据指定的角色获取或构建单例 {@link ReactAgent}
     *
     * @param role 子智能体角色
     * @return 角色对应的专业 ReactAgent
     * @throws IllegalArgumentException 若角色为 SUPERVISOR 或 null
     */
    public ReactAgent getSubagent(AgentRole role) {
        Objects.requireNonNull(role, "AgentRole cannot be null");
        if (!role.isSubagent()) {
            throw new IllegalArgumentException("Cannot create Subagent for non-subagent role: " + role);
        }
        return agentCache.computeIfAbsent(role, this::buildSubagent);
    }

    /**
     * 为指定角色构建并包装安全上下文工具回调 (ToolCallback)
     *
     * @param role 子智能体角色
     * @param operationLedger 操作流水账本
     * @param evidenceCoordinator 证据归集协调器
     * @param actionGuardrail 动作级守卫（可选）
     * @return 封装后的 ToolCallback
     */
    public ToolCallback createSubagentToolCallback(
            AgentRole role,
            AgentOperationLedger operationLedger,
            EvidenceMergeCoordinator evidenceCoordinator,
            ActionGuardrail actionGuardrail
    ) {
        ReactAgent subagent = getSubagent(role);
        ToolCallback rawCallback = AgentTool.getFunctionToolCallback(subagent);
        return new ContextualAgentToolCallback(
                rawCallback,
                role,
                operationLedger,
                evidenceCoordinator,
                actionGuardrail,
                agentTracer
        );
    }

    /**
     * 一键生成所有已注册子智能体的上下文安全 ToolCallback 列表（供 Supervisor 统一挂载）
     *
     * @param operationLedger 操作流水账本
     * @param evidenceCoordinator 证据归集协调器
     * @param actionGuardrail 动作级守卫（可选）
     * @return 包含所有子智能体的 ToolCallback 列表
     */
    public List<ToolCallback> createAllSubagentToolCallbacks(
            AgentOperationLedger operationLedger,
            EvidenceMergeCoordinator evidenceCoordinator,
            ActionGuardrail actionGuardrail
    ) {
        return AgentRole.subagentRoles().stream()
                .map(role -> createSubagentToolCallback(role, operationLedger, evidenceCoordinator, actionGuardrail))
                .toList();
    }

    private ReactAgent buildSubagent(AgentRole role) {
        log.info("[SUBAGENT-FACTORY] Building role-driven subagent: role={}, agentName={}",
                role, role.getAgentName());

        Object toolProvider = resolveToolProvider(role);

        return ReactAgent.builder()
                .name(role.getAgentName())
                .description(role.getDescription())
                .systemPrompt(SubagentPrompts.getSystemPrompt(role))
                .model(chatModel)
                .methodTools(toolProvider)
                .saver(new MemorySaver())
                .build();
    }

    private Object resolveToolProvider(AgentRole role) {
        return switch (role) {
            case FUND_RESEARCH -> fundResearchTools;
            case STOCK_RESEARCH -> stockResearchTools;
            case MARKET_RESEARCH -> marketResearchTools;
            case RISK_REVIEW, REPORT -> evidenceTools;
            case SUPERVISOR -> throw new IllegalArgumentException("SUPERVISOR does not have standard subagent tool provider");
        };
    }
}
