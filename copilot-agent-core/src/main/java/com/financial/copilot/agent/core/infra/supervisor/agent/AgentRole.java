package com.financial.copilot.agent.core.infra.supervisor.agent;

/**
 * <h1>Supervisor 体系智能体角色枚举</h1>
 * 包含唯一的总控 Supervisor 以及五个各司其职的金融专业 Subagent。
 */
public enum AgentRole {
    /**
     * 总控决策智能体，唯一直接对接外部用户输入，负责意图理解与任务委派。
     */
    SUPERVISOR,

    /**
     * 基金投研智能体：负责公募基金筛选、单基金体检分析、基金对比。
     */
    FUND_RESEARCH,

    /**
     * 股票投研智能体：负责股票筛选、量化分析、估值模型审计。
     */
    STOCK_RESEARCH,

    /**
     * 市场与宏观智能体：负责金融领域知识库 RAG、宏观周期与市场背景检索。
     */
    MARKET_RESEARCH,

    /**
     * 风险合规审查智能体：只读审查已生成的证据完整性、事实冲突与风险披露。
     */
    RISK_REVIEW,

    /**
     * 研报撰写智能体：将通过合规复核的结构化事实证据合成最终投研报告。
     */
    REPORT
}
