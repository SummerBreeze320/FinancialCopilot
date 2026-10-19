package com.financial.copilot.domain.business.stock.port;

import com.financial.copilot.common.stock.dto.StockMetricsDTO;
import com.financial.copilot.common.stock.dto.StockScreeningCriteria;
import com.financial.copilot.domain.business.stock.entity.StockInfo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * <h1>股票数据访问 SPI 端口契约</h1>
 * <p>
 * 遵循可扩展架构，为股票领域（Stock Domain）定义的底层数据提取与指标计算标准端口。
 * 涵盖基础档案、量化指标、财务季报、分红历史与十大股东等全维度数据访问。
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

    /**
     * 获取股票最近 N 个季度的核心财务指标
     *
     * @param stockCode 股票代码
     * @param limit     最大季度数
     * @return 财务指标列表 (按季度倒序)
     */
    List<StockFinancialSummary> getFinancialHistory(String stockCode, int limit);

    /**
     * 获取股票的分红送转历史
     *
     * @param stockCode 股票代码
     * @return 分红记录列表
     */
    List<StockDividendRecord> getDividendHistory(String stockCode);

    /**
     * 获取股票最新一期的十大股东明细
     *
     * @param stockCode 股票代码
     * @return 十大股东列表
     */
    List<StockShareholderRecord> getTopShareholders(String stockCode);

    /**
     * 获取股票最近 N 个交易日的收盘价时序 (按时间正序)
     * <p>
     * 用于 RSI/MACD/SMA/EMA/年化波动率等技术指标计算。
     * </p>
     *
     * @param stockCode 股票代码
     * @param limit     最大返回天数
     * @return 收盘价列表 (按时间正序排列)
     */
    List<BigDecimal> getDailyClosePrices(String stockCode, int limit);

    // ── 领域层轻量 DTO ──────────────────────────────────────────────

    /**
     * 股票季度财务摘要 (领域层轻量 DTO)
     */
    record StockFinancialSummary(
        String stockCode,
        String reportQuarter,
        BigDecimal revenueBillion,
        BigDecimal netProfitBillion,
        BigDecimal eps,
        BigDecimal roe,
        BigDecimal grossMargin,
        BigDecimal netMargin,
        BigDecimal debtToAssetRatio
    ) {}

    /**
     * 分红送转记录
     */
    record StockDividendRecord(
        String stockCode,
        String dividendYear,
        BigDecimal cashDividendPer10,
        BigDecimal dividendRatio,
        BigDecimal payoutRatio,
        LocalDate exDividendDate
    ) {}

    /**
     * 十大股东记录
     */
    record StockShareholderRecord(
        String stockCode,
        String reportQuarter,
        int rankOrder,
        String shareholderName,
        String shareholderType,
        BigDecimal holdingRatio,
        String holdingChange
    ) {}
}
