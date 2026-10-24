package com.financial.copilot.agent.core.infra.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * <h1>Supervisor 体系智能体角色契约实体 (AgentRole)</h1>
 * <p>
 * 定义角色驱动架构的核心元数据。每个角色内聚其代理标识符 (agentName)、中文展示名 (title)、
 * 调度功能描述 (description) 以及是否属于被总控调度的 Subagent 标记。
 * </p>
 *
 * @author FinancialCopilot
 */
public enum AgentRole {

    /**
     * 总控决策智能体，唯一直接对接外部用户输入，负责意图理解、技能匹配与任务委派。
     */
    SUPERVISOR(
            "research_supervisor",
            "金融投研总控调度主管",
            "金融投研中心调度决策主管，负责意图识别、规划拆解、动态委派子智能体与全局成果裁决",
            false
    ),

    /**
     * 基金投研智能体：负责公募基金筛选、单基金体检分析、基金对比与持仓穿透。
     */
    FUND_RESEARCH(
            "fund_research_agent",
            "公募基金投研分析师",
            "负责公募基金档案查询、量化指标体检（收益/回撤/夏普/卡玛）、多维筛选与季报持仓穿透",
            true
    ),

    /**
     * 股票投研智能体：负责股票筛选、量化分析、估值模型审计。
     */
    STOCK_RESEARCH(
            "stock_research_agent",
            "A股股票投研专家",
            "负责 A 股股票全维档案、基本面财务指标（ROE、PE、PB、股息率）与量化分析",
            true
    ),

    /**
     * 市场与宏观智能体：负责金融领域知识库 RAG、宏观周期与市场背景检索。
     */
    MARKET_RESEARCH(
            "market_research_agent",
            "市场宏观与知识图谱专家",
            "负责金融指标与板块知识库混合召回、指标口径释义、知识图谱重合持仓对比与股票穿透",
            true
    ),

    /**
     * 风险合规审查智能体：只读审查已生成的证据完整性、事实冲突与风险披露。
     */
    RISK_REVIEW(
            "risk_review_agent",
            "合规与风控总监",
            "负责只读审查已生成的证据完整性、事实冲突、投资人适格匹配与风险合规披露",
            true
    ),

    /**
     * 研报撰写智能体：将通过合规复核的结构化事实证据合成最终投研报告。
     */
    REPORT(
            "report_agent",
            "机构研报合成专家",
            "负责将通过合规复核的结构化事实证据合成排版精美、结构严谨的最终金融投研报告",
            true
    );

    private final String agentName;
    private final String title;
    private final String description;
    private final boolean subagent;

    private static final List<AgentRole> SUBAGENT_ROLES = Collections.unmodifiableList(
            Arrays.stream(values())
                    .filter(AgentRole::isSubagent)
                    .toList()
    );

    AgentRole(String agentName, String title, String description, boolean subagent) {
        this.agentName = agentName;
        this.title = title;
        this.description = description;
        this.subagent = subagent;
    }

    /**
     * 获取智能体唯一定义名称（对应模型工具调用名称）
     *
     * @return 智能体名称
     */
    public String getAgentName() {
        return agentName;
    }

    /**
     * 获取智能体中文展示标题
     *
     * @return 中文名称
     */
    public String getTitle() {
        return title;
    }

    /**
     * 获取智能体功能描述（供模型进行 Tool 描述与意图路由）
     *
     * @return 功能描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 是否属于由 Supervisor 调度的专业子智能体 (Subagent)
     *
     * @return true 若为 Subagent，false 若为 Supervisor 本身
     */
    public boolean isSubagent() {
        return subagent;
    }

    /**
     * 获取所有由 Supervisor 调度的子智能体角色列表
     *
     * @return 子智能体角色不可变列表
     */
    public static List<AgentRole> subagentRoles() {
        return SUBAGENT_ROLES;
    }

    /**
     * 根据 agentName 反查对应的智能体角色
     *
     * @param agentName 智能体定义名
     * @return 匹配的 AgentRole
     * @throws IllegalArgumentException 若未找到对应角色
     */
    public static AgentRole fromAgentName(String agentName) {
        Objects.requireNonNull(agentName, "agentName cannot be null");
        for (AgentRole role : values()) {
            if (role.agentName.equalsIgnoreCase(agentName) || role.name().equalsIgnoreCase(agentName)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown AgentRole with agentName: " + agentName);
    }
}
