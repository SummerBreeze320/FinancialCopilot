package com.financial.copilot.agent.core.agents.fund;

import com.financial.copilot.agent.core.service.DeepSeekClientService;
import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>公募基金横向深度对标与对比专员 Agent (Fund Comparator Agent)</h1>
 * <p>
 * 职责：负责在两只公募基金之间执行深度对称数据拉取，包括两者的量化业绩指标、前十大重仓持仓结构以及季度研报策略。
 * 结合资深基金对标 Prompt，输出包含对标表格、持仓差异与投资哲学异同的专业对标分析 Markdown 报告。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundComparatorAgent {

    /**
     * 公募基金量化分析工具
     */
    private final FundQuantAnalysisTool quantTool;

    /**
     * 公募基金重仓持股查询工具
     */
    private final FundHoldingsQueryTool holdingsTool;

    /**
     * 公募基金季报与定性观点检索工具
     */
    private final FundReportRetrieverTool reportTool;

    /**
     * 大模型客户端服务（用于生成深度横向归因分析）
     */
    private final DeepSeekClientService clientService;

    /**
     * 基金横向对标专员 System Prompt
     */
    private static final String SYSTEM_PROMPT = """
        你是一个资深基金对标与投研对比专家 ComparatorAgent。
        请根据输入的两只基金标的的客观量化数据（年化收益、最大回撤、夏普、卡玛）、重仓持股穿透和季报观点，
        撰写一份客观、对称的横向对比与归因分析：
        
        【分析维度】:
        1. 风险-收益特征矩阵：用 Markdown 表格横向对照双方关键量化指标；
        2. 资产配置与行业风格差异：对比前十大重仓股重合度、行业集中度与风格偏向（大盘价值 vs 成长）；
        3. 投资哲学与言行一致性：对比双方经理在季报定性观点中的表态与实际持仓运作；
        4. 综合优劣势评价与不同市场环境适应性说明。
        
        【严格防幻觉纪律】:
        严格基于输入事实数据，严禁编造任何未披露数据。
        """;

    /**
     * 构造函数，自动装配底层数据工具与大模型服务
     *
     * @param quantTool     量化分析工具
     * @param holdingsTool  持仓穿透工具
     * @param reportTool    研报检索工具
     * @param clientService 大模型客户端调用服务
     */
    public FundComparatorAgent(FundQuantAnalysisTool quantTool,
                               FundHoldingsQueryTool holdingsTool,
                               FundReportRetrieverTool reportTool,
                               DeepSeekClientService clientService) {
        this.quantTool = quantTool;
        this.holdingsTool = holdingsTool;
        this.reportTool = reportTool;
        this.clientService = clientService;
    }

    /**
     * 兼容历史三参数构造函数（便于简化测试）
     *
     * @param quantTool    量化工具
     * @param holdingsTool 持仓工具
     * @param reportTool   研报工具
     */
    public FundComparatorAgent(FundQuantAnalysisTool quantTool,
                               FundHoldingsQueryTool holdingsTool,
                               FundReportRetrieverTool reportTool) {
        this(quantTool, holdingsTool, reportTool, null);
    }

    /**
     * 采集双标的对称数据上下文，并生成深度横向对标分析
     *
     * @param codeA 标的A基金代码
     * @param codeB 标的B基金代码
     * @return 对称事实与归因分析 Markdown 文本
     */
    public String compareFunds(String codeA, String codeB) {
        log.info("[FUND-COMPARATOR] 正在对标采集两只基金数据事实并执行深度归因: codeA={}, codeB={}", codeA, codeB);

        String metricsA = quantTool.getFundMetrics(codeA, null, null);
        String metricsB = quantTool.getFundMetrics(codeB, null, null);

        String holdingsA = holdingsTool.getTopHoldings(codeA, null);
        String holdingsB = holdingsTool.getTopHoldings(codeB, null);

        String reportA = reportTool.getLatestQuarterlyReportView(codeA);
        String reportB = reportTool.getLatestQuarterlyReportView(codeB);

        String rawFacts = """
            === 双基金标的横向对标事实输入 (Tool-as-Truth) ===
            【标的 A (基金代码: %s)】:
            - 量化指标: %s
            - 持仓穿透: %s
            - 季报定性展望: %s

            ----------------------------------------
            【标的 B (基金代码: %s)】:
            - 量化指标: %s
            - 持仓穿透: %s
            - 季报定性展望: %s
            """.formatted(codeA, metricsA, holdingsA, reportA, codeB, metricsB, holdingsB, reportB);

        if (clientService != null) {
            try {
                String prompt = "【对比诉求】: 对比基金 " + codeA + " 与 " + codeB + " 的综合表现与风格差异\n\n" + rawFacts;
                String comparisonAnalysis = clientService.chat(SYSTEM_PROMPT, prompt);
                return rawFacts + "\n\n=== 智能对标深度归因 ===\n" + comparisonAnalysis;
            } catch (Exception e) {
                log.warn("[FUND-COMPARATOR] 调用 LLM 深度对比失败，返回客观事实: error={}", e.getMessage());
            }
        }

        return rawFacts;
    }
}
