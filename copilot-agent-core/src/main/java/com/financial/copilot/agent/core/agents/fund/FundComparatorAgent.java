package com.financial.copilot.agent.core.agents.fund;

import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>公募基金横向对比专员 Agent (Fund Comparator)</h1>
 * <p>
 * 职责：负责在两只公募基金之间执行深度对称数据拉取，包括两者的量化业绩指标、前十大重仓持仓结构以及季度研报策略，
 * 输出结构化横向对标事实输入，帮助主编智能体进行优劣劣势客观评判。
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
     * 构造函数，自动装配底层数据工具
     *
     * @param quantTool    量化分析工具
     * @param holdingsTool 持仓穿透工具
     * @param reportTool   研报检索工具
     */
    public FundComparatorAgent(FundQuantAnalysisTool quantTool,
                               FundHoldingsQueryTool holdingsTool,
                               FundReportRetrieverTool reportTool) {
        this.quantTool = quantTool;
        this.holdingsTool = holdingsTool;
        this.reportTool = reportTool;
    }

    /**
     * 采集双标的对称数据上下文，生成横向对标数据包
     *
     * @param codeA 标的A基金代码
     * @param codeB 标的B基金代码
     * @return 对称事实 Markdown 文本
     */
    public String compareFunds(String codeA, String codeB) {
        log.info("[FUND-COMPARATOR] 正在对标采集两只基金数据事实: codeA={}, codeB={}", codeA, codeB);

        String metricsA = quantTool.getFundMetrics(codeA, null, null);
        String metricsB = quantTool.getFundMetrics(codeB, null, null);

        String holdingsA = holdingsTool.getTopHoldings(codeA, null);
        String holdingsB = holdingsTool.getTopHoldings(codeB, null);

        String reportA = reportTool.getLatestQuarterlyReportView(codeA);
        String reportB = reportTool.getLatestQuarterlyReportView(codeB);

        return """
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
    }
}
