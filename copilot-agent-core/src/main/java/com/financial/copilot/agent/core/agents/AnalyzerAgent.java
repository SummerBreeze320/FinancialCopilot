package com.financial.copilot.agent.core.agents;

import com.financial.copilot.agent.core.agents.fund.FundAnalyzerAgent;
import com.financial.copilot.agent.core.agents.stock.StockAnalyzerAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>多资产全景体检与深度分析专员统一门面 (Unified Analyzer Agent Facade)</h1>
 * <p>
 * 职责：作为公募基金全景分析专员 {@link FundAnalyzerAgent} 与股票全景分析专员 {@link StockAnalyzerAgent} 的调度门面。
 * 能够根据标的代码格式或调用指令，自动分发至对应的资产体检工具链。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class AnalyzerAgent {

    /**
     * 公募基金全景分析专员
     */
    private final FundAnalyzerAgent fundAnalyzerAgent;

    /**
     * 股票全景分析专员
     */
    private final StockAnalyzerAgent stockAnalyzerAgent;

    /**
     * 全参构造函数
     *
     * @param fundAnalyzerAgent  基金分析专员
     * @param stockAnalyzerAgent 股票分析专员
     */
    public AnalyzerAgent(FundAnalyzerAgent fundAnalyzerAgent, StockAnalyzerAgent stockAnalyzerAgent) {
        this.fundAnalyzerAgent = fundAnalyzerAgent;
        this.stockAnalyzerAgent = stockAnalyzerAgent;
    }

    /**
     * 兼容单入参的基金构造函数
     *
     * @param fundAnalyzerAgent 基金分析专员
     */
    public AnalyzerAgent(FundAnalyzerAgent fundAnalyzerAgent) {
        this(fundAnalyzerAgent, null);
    }

    /**
     * 采集并组织单只公募基金的全维度体检数据上下文
     *
     * @param fundCode 6位基金代码
     * @return 格式化后的基金体检事实文本
     */
    public String analyzeFund(String fundCode) {
        log.info("[ANALYZER-FACADE] 转发基金体检请求至基金分析专员: fundCode={}", fundCode);
        return fundAnalyzerAgent.analyzeFund(fundCode);
    }

    /**
     * 采集并组织单只股票的全维度体检数据上下文
     *
     * @param stockCode 股票代码 (如 600519.SH)
     * @return 格式化后的个股体检事实文本
     */
    public String analyzeStock(String stockCode) {
        if (stockAnalyzerAgent != null) {
            log.info("[ANALYZER-FACADE] 转发股票体检请求至股票分析专员: stockCode={}", stockCode);
            return stockAnalyzerAgent.analyzeStock(stockCode);
        }
        return "【股票分析】: 暂未接入股票分析专员实例";
    }

    /**
     * 通用多资产深度体检路由接口
     *
     * @param assetCode 标的代码（自动根据代码后缀或规则判别属于基金还是个股）
     * @return 深度体检事实 Markdown
     */
    public String analyzeAsset(String assetCode) {
        if (assetCode != null && (assetCode.toUpperCase().endsWith(".SH") || assetCode.toUpperCase().endsWith(".SZ"))) {
            return analyzeStock(assetCode);
        }
        return analyzeFund(assetCode);
    }
}
