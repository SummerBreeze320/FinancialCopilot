package com.financial.copilot.agent.tools.stock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>股票个股基本面与量化指标分析只读工具 (Stock Quant Analysis Tool)</h1>
 * <p>
 * 供股票分析 Agent 查询指标数据。待配置外部 HTTP 股票服务。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class StockQuantAnalysisTool {

    public StockQuantAnalysisTool() {
    }

    /**
     * 查询指定个股的量化与基本面指标
     *
     * @param stockCode 股票代码 (如 "600519.SH")
     * @return 包含估值、财务与贝塔指标的 JSON 文本
     */
    public String getStockMetrics(String stockCode) {
        log.info("[TOOL CALL-STOCK] 正在查询个股指标: stockCode={}", stockCode);
        return "{\"stockCode\":\"" + stockCode + "\",\"pe\":30.5,\"pb\":8.2,\"roe\":0.25,\"dividendYield\":0.02}";
    }
}
