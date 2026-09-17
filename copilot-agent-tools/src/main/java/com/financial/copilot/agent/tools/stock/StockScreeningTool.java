package com.financial.copilot.agent.tools.stock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * <h1>股票智能筛选只读工具 (Stock Screening Tool)</h1>
 * <p>
 * 供 StockScreenerAgent 使用。待接入外部 HTTP 股票筛选服务。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class StockScreeningTool {

    public StockScreeningTool() {
    }

    public String screenStocks(Map<String, Object> criteria) {
        log.info("[TOOL CALL-STOCK] 正在执行股票多因子筛选: criteria={}", criteria);
        return "[{\"stockCode\":\"600519.SH\",\"stockName\":\"贵州茅台\"}]";
    }
}
