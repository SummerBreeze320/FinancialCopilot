package com.financial.copilot.agent.core.agents.stock;

import com.financial.copilot.agent.tools.stock.StockQuantAnalysisTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>股票个股全景深度体检与分析专员 Agent (Stock Analyzer Agent)</h1>
 * <p>
 * 职责：作为股票领域的单标的事实采集专员，挂载股票量化指标工具 {@link StockQuantAnalysisTool}，
 * 组装出全面、客观、真实的单只股票基本面事实上下文（估值、ROE、分红率、Beta系数等），为报告合成提供客观事实。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class StockAnalyzerAgent {

    private final StockQuantAnalysisTool stockQuantAnalysisTool;

    /**
     * 构造函数，注入股票量化分析工具
     *
     * @param stockQuantAnalysisTool 股票量化工具
     */
    public StockAnalyzerAgent(StockQuantAnalysisTool stockQuantAnalysisTool) {
        this.stockQuantAnalysisTool = stockQuantAnalysisTool;
    }

    /**
     * 采集并组织单只股票的全维度体检数据上下文
     *
     * @param stockCode 股票代码（例如 "600519.SH"）
     * @return 格式化后的个股全景体检数据事实 Markdown 报告
     */
    public String analyzeStock(String stockCode) {
        log.info("[STOCK-ANALYZER] 正在进行个股全景数据采集与基本面分析: stockCode={}", stockCode);
        String metricsJson = stockQuantAnalysisTool.getStockMetrics(stockCode);

        return """
            === 上市公司个股全景体检数据事实 (Tool-as-Truth) ===
            【股票代码】: %s
            【核心估值与财务特征】:
            %s
            """.formatted(stockCode, metricsJson);
    }
}
