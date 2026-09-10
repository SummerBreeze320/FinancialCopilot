package com.financial.copilot.agent.core.agents;

import com.financial.copilot.agent.tools.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.FundReportRetrieverTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 基金横向对标专员 Agent (Comparator)
 * 并行拉取双方真实量化数据与季报文本，为研报主编提供对称事实输入。
 */
@Slf4j
@Component
public class ComparatorAgent {

    private final FundQuantAnalysisTool quantTool;
    private final FundHoldingsQueryTool holdingsTool;
    private final FundReportRetrieverTool reportTool;

    public ComparatorAgent(FundQuantAnalysisTool quantTool,
                           FundHoldingsQueryTool holdingsTool,
                           FundReportRetrieverTool reportTool) {
        this.quantTool = quantTool;
        this.holdingsTool = holdingsTool;
        this.reportTool = reportTool;
    }

    /**
     * 采集双标的对称数据上下文
     */
    public String compareFunds(String codeA, String codeB) {
        log.info("[COMPARATOR] 正在对标采集双方数据: codeA={}, codeB={}", codeA, codeB);

        String metricsA = quantTool.getFundMetrics(codeA, null, null);
        String metricsB = quantTool.getFundMetrics(codeB, null, null);

        String holdingsA = holdingsTool.getTopHoldings(codeA, null);
        String holdingsB = holdingsTool.getTopHoldings(codeB, null);

        String reportA = reportTool.getLatestQuarterlyReportView(codeA);
        String reportB = reportTool.getLatestQuarterlyReportView(codeB);

        return """
            === 双标的横向对标事实输入 (Tool-as-Truth) ===
            【标的 A (代码: %s)】:
            - 量化指标: %s
            - 持仓穿透: %s
            - 季报定性展望: %s

            ----------------------------------------
            【标的 B (代码: %s)】:
            - 量化指标: %s
            - 持仓穿透: %s
            - 季报定性展望: %s
            """.formatted(codeA, metricsA, holdingsA, reportA, codeB, metricsB, holdingsB, reportB);
    }
}
