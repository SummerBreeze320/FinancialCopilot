package com.financial.copilot.domain.stock.port;

import com.financial.copilot.common.stock.dto.StockMetricsDTO;
import com.financial.copilot.common.stock.dto.StockScreeningCriteria;
import com.financial.copilot.domain.stock.entity.StockInfo;

import java.util.List;
import java.util.Optional;

/**
 * <h1>股票数据访问 SPI 端口契约</h1>
 * <p>
 * 遵循可扩展架构，为股票领域（Stock Domain）定义的底层数据提取与指标计算标准端口。
 * </p>
 *
 * @author FinancialCopilot
 */
public interface StockDataPort {

    /**
     * 根据股票代码获取单只股票的基础档案
     *
     * @param stockCode 股票代码 (如 600519)
     * @return 股票实体 Optional
     */
    Optional<StockInfo> getStockByCode(String stockCode);

    /**
     * 依据多维量化与基本面条件筛选股票标的
     *
     * @param criteria 股票筛选条件 DSL
     * @return 命中条件的股票列表
     */
    List<StockInfo> screenStocks(StockScreeningCriteria criteria);

    /**
     * 获取股票的最新量化与估值指标 (PE, PB, ROE, 股息率等)
     *
     * @param stockCode 股票代码
     * @return 股票指标 DTO
     */
    StockMetricsDTO getStockMetrics(String stockCode);
}
