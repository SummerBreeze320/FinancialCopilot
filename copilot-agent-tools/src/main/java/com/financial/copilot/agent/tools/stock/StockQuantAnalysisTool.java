package com.financial.copilot.agent.tools.stock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.common.stock.dto.StockMetricsDTO;
import com.financial.copilot.domain.stock.port.StockDataPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>股票个股基本面与量化指标分析只读工具 (Stock Quant Analysis Tool)</h1>
 * <p>
 * 职责：遵循 Tool-as-Truth 规范，为股票分析 Agent 提供单只股票的全维度估值、成长性、盈利能力与风险指标数据。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class StockQuantAnalysisTool {

    private final StockDataPort stockDataPort;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数，注入股票数据端口
     *
     * @param stockDataPort 股票数据访问端口
     * @param objectMapper  JSON 映射器
     */
    public StockQuantAnalysisTool(StockDataPort stockDataPort, ObjectMapper objectMapper) {
        this.stockDataPort = stockDataPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询指定个股的量化与基本面指标
     *
     * @param stockCode 股票代码 (如 "600519.SH")
     * @return 包含估值、财务与贝塔指标的 JSON 文本
     */
    public String getStockMetrics(String stockCode) {
        log.info("[TOOL CALL-STOCK] 正在查询个股基本面与量化指标: stockCode={}", stockCode);
        try {
            StockMetricsDTO metrics = stockDataPort.getStockMetrics(stockCode);
            return objectMapper.writeValueAsString(metrics);
        } catch (Exception e) {
            log.error("[TOOL CALL-STOCK] 获取个股量化指标失败: stockCode={}, error={}", stockCode, e.getMessage(), e);
            return "{}";
        }
    }
}
