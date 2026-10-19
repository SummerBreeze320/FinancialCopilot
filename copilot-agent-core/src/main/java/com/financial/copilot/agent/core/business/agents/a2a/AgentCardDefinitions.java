package com.financial.copilot.agent.core.business.agents.a2a;

import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;

import java.util.List;

/**
 * <h1>标准 A2A AgentCard 元数据工厂</h1>
 * <p>
 * 为投研系统的各个领域 SubAgent 提供标准化的 {@link AgentCard} 名片定义。
 * </p>
 *
 * @author FinancialCopilot
 */
public final class AgentCardDefinitions {

    private static final String PROTOCOL_VERSION = "0.2.5";
    private static final String AGENT_VERSION = "1.0.0";

    private AgentCardDefinitions() {
    }

    public static AgentCard fundScreenerCard() {
        return buildCard(
                "FundScreenerAgent",
                "公募基金初筛排查智能体，支持对齐指标助记码与板块并筛选候选基金池",
                "fund-screening",
                "基金条件筛选",
                List.of("SCREENING", "FUND")
        );
    }

    public static AgentCard stockScreenerCard() {
        return buildCard(
                "StockScreenerAgent",
                "个股行情与财务条件筛选智能体，根据行业板块与基本面指标筛选股票池",
                "stock-screening",
                "股票条件筛选",
                List.of("SCREENING", "STOCK")
        );
    }

    public static AgentCard fundAnalyzerAgentCard() {
        return buildCard(
                "FundAnalyzerAgent",
                "公募基金量化与定性分析智能体，执行指标测算、重仓穿透与研报检索",
                "fund-analysis",
                "基金量化分析",
                List.of("BATCH_ANALYSIS", "FUND")
        );
    }

    public static AgentCard stockAnalyzerCard() {
        return buildCard(
                "StockAnalyzerAgent",
                "个股基本面与财务穿透分析智能体，负责深度量化与定性研报",
                "stock-analysis",
                "股票基本面分析",
                List.of("BATCH_ANALYSIS", "STOCK")
        );
    }

    public static AgentCard fundComparatorCard() {
        return buildCard(
                "FundComparatorAgent",
                "多只公募基金深度对比与评级智能体，生成横向对比矩阵与优劣势洞察",
                "fund-comparison",
                "基金深度对比",
                List.of("COMPARISON", "DEEP_DIVE", "FUND")
        );
    }

    public static AgentCard stockComparatorCard() {
        return buildCard(
                "StockComparatorAgent",
                "多只个股横向对比与竞争壁垒分析智能体",
                "stock-comparison",
                "股票深度对比",
                List.of("COMPARISON", "DEEP_DIVE", "STOCK")
        );
    }

    public static AgentCard reportSynthesizerCard() {
        return buildCard(
                "ReportSynthesizerAgent",
                "投研研报终审与合成聚合专家，接收经验证产物并进行降级披露与最终研报编排",
                "report-synthesis",
                "研报终审合成",
                List.of("SYNTHESIS", "ALL", "FUND", "STOCK")
        );
    }

    private static AgentCard buildCard(String name, String description, String skillId, String skillName, List<String> tags) {
        AgentCapabilities capabilities = new AgentCapabilities.Builder()
                .streaming(false)
                .pushNotifications(false)
                .stateTransitionHistory(false)
                .build();

        AgentSkill skill = new AgentSkill.Builder()
                .id(skillId)
                .name(skillName)
                .description(description)
                .tags(tags)
                .inputModes(List.of("application/json", "text/plain"))
                .outputModes(List.of("application/json"))
                .build();

        return new AgentCard.Builder()
                .name(name)
                .description(description)
                .url("http://localhost:8080/api/a2a/agents/" + name.toLowerCase(java.util.Locale.ROOT))
                .version(AGENT_VERSION)
                .protocolVersion(PROTOCOL_VERSION)
                .capabilities(capabilities)
                .skills(List.of(skill))
                .defaultInputModes(List.of("application/json", "text/plain"))
                .defaultOutputModes(List.of("application/json"))
                .build();
    }
}
