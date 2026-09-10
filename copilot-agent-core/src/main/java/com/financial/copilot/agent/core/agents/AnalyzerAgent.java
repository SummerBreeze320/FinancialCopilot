package com.financial.copilot.agent.core.agents;

import com.financial.copilot.agent.tools.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.FundReportRetrieverTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 单标的多维全景分析专员 Agent (Analyzer)
 * 强制挂载量化工具与持仓工具，作为事实采集器。
 */
@Slf4j
@Component
public class AnalyzerAgent {

    private final FundQuantAnalysisTool quantTool;
    private final FundHoldingsQueryTool holdingsTool;
    private final FundReportRetrieverTool reportTool;

    public AnalyzerAgent(FundQuantAnalysisTool quantTool,
                         FundHoldingsQueryTool holdingsTool,
                         FundReportRetrieverTool reportTool) {
        this.quantTool = quantTool;
        this.holdingsTool = holdingsTool;
        this.reportTool = reportTool;
    }

    /**
     * 采集并组织单只基金的全维度体检数据上下文
     */
    public String analyzeFund(String fundCode) {
        log.info("[ANALYZER] 正在进行全景数据采集: fundCode={}", fundCode);

        String metricsJson = quantTool.getFundMetrics(fundCode, null, null);
        String holdingsJson = holdingsTool.getTopHoldings(fundCode, null);
        String reportView = reportTool.getLatestQuarterlyReportView(fundCode);

        return """
            === 基金全景体检数据事实 ===
            【基金代码】: %s
            【量化收益与风险特征】:
            %s
            
            【前十大重仓持股与行业穿透】:
            %s
            
            【基金经理定性季报策略观点】:
            %s
            """.formatted(fundCode, metricsJson, holdingsJson, reportView);
    }
}
