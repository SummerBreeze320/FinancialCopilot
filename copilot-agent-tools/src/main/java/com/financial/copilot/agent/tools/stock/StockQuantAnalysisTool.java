package com.financial.copilot.agent.tools.stock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.common.stock.dto.StockMetricsDTO;
import com.financial.copilot.domain.stock.port.StockDataPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * <h1>股票个股基本面与量化指标分析只读工具 (Stock Quant Analysis Tool)</h1>
 * <p>
 * 供股票分析 Agent 提供单只股票的全维度估值、财务与风险指标数据。数据源设计为外部 HTTP 接口调用。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class StockQuantAnalysisTool {

    private final StockDataPort stockDataPort;
    private final ObjectMapper objectMapper;

    public StockQuantAnalysisTool(@Autowired(required = false) StockDataPort stockDataPort, ObjectMapper objectMapper) {
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
        if (stockDataPort == null) {
            log.info("[TOOL CALL-STOCK] 当前未挂载本地股票数据库端口，待配置外部 HTTP 股票指标接口");
            return "{}";
        }
        try {
            StockMetricsDTO metrics = stockDataPort.getStockMetrics(stockCode);
            return objectMapper.writeValueAsString(metrics);
        } catch (Exception e) {
            log.error("[TOOL CALL-STOCK] 获取个股量化指标失败: stockCode={}, error={}", stockCode, e.getMessage(), e);
            return "{}";
        }
    }
}
