package com.financial.copilot.agent.core.agents.fund;

import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>公募基金全景体检与深度分析专员 Agent (Fund Analyzer)</h1>
 * <p>
 * 职责：作为公募基金领域的单标的事实采集与分析专员，通过挂载量化指标分析工具 {@link FundQuantAnalysisTool}、
 * 季度重仓持股穿透工具 {@link FundHoldingsQueryTool} 以及季报观点检索工具 {@link FundReportRetrieverTool}，
 * 组装出全面、客观、严谨的单只基金体检数据上下文（Tool-as-Truth），为研报主编提供真实事实底座。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundAnalyzerAgent {

    /**
     * 公募基金量化指标计算与风险收益分析工具
     */
    private final FundQuantAnalysisTool quantTool;

    /**
     * 公募基金季度持仓穿透与行业配置分析工具
     */
    private final FundHoldingsQueryTool holdingsTool;

    /**
     * 公募基金季报观点与定性投研文本检索工具
     */
    private final FundReportRetrieverTool reportTool;

    /**
     * 构造函数，由 Spring 容器自动注入底层只读工具组件
     *
     * @param quantTool    公募基金量化指标分析工具
     * @param holdingsTool 公募基金持仓透视工具
     * @param reportTool   公募基金研报检索工具
     */
    public FundAnalyzerAgent(FundQuantAnalysisTool quantTool,
                             FundHoldingsQueryTool holdingsTool,
                             FundReportRetrieverTool reportTool) {
        this.quantTool = quantTool;
        this.holdingsTool = holdingsTool;
        this.reportTool = reportTool;
    }

    /**
     * 采集并组织单只公募基金的全维度体检数据上下文
     *
     * @param fundCode 6位公募基金代码（例如 "000001"）
     * @return 格式化后的单基金全景体检数据事实 Markdown 报告
     */
    public String analyzeFund(String fundCode) {
        log.info("[FUND-ANALYZER] 正在进行公募基金全景数据采集与深度分析: fundCode={}", fundCode);

        // 1. 量化风险收益指标
        String metricsJson = quantTool.getFundMetrics(fundCode, null, null);

        // 2. 前十大重仓股与行业分布
        String holdingsJson = holdingsTool.getTopHoldings(fundCode, null);

        // 3. 基金经理最新季度定性观点与展望
        String reportView = reportTool.getLatestQuarterlyReportView(fundCode);

        return """
            === 公募基金全景体检数据事实 (Tool-as-Truth) ===
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
