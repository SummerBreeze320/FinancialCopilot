package com.financial.copilot.agent.tools.stock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.common.stock.dto.StockScreeningCriteria;
import com.financial.copilot.domain.stock.entity.StockInfo;
import com.financial.copilot.domain.stock.port.StockDataPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <h1>股票智能多因子初筛只读工具 (Stock Screening Tool)</h1>
 * <p>
 * 职责：遵循 Tool-as-Truth 规范，为股票筛选 Agent 提供只读的基本面与量化指标初筛能力。
 * 根据输入的结构化筛选条件 {@link StockScreeningCriteria}，检索符合市盈率、市净率、ROE、市值与行业板块要求的上市公司股票列表。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class StockScreeningTool {

    private final StockDataPort stockDataPort;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数，自动注入股票数据访问端口与 JSON 转换器
     *
     * @param stockDataPort 股票数据端口
     * @param objectMapper  对象映射器
     */
    public StockScreeningTool(StockDataPort stockDataPort, ObjectMapper objectMapper) {
        this.stockDataPort = stockDataPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据多因子筛选条件查询符合条件的股票池
     *
     * @param criteria 股票结构化筛选条件
     * @return 命中的股票实体 JSON 数组字符串
     */
    public String screenStocks(StockScreeningCriteria criteria) {
        log.info("[TOOL CALL-STOCK] 正在执行多因子股票筛选: criteria={}", criteria);
        try {
            List<StockInfo> results = stockDataPort.screenStocks(criteria);
            return objectMapper.writeValueAsString(results);
        } catch (Exception e) {
            log.error("[TOOL CALL-STOCK] 股票筛选异常: error={}", e.getMessage(), e);
            return "[]";
        }
    }
}
