package com.financial.copilot.common.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <h1>股票标的量化与基本面指标数据传输对象</h1>
 * <p>
 * 为投研平台中的个股基本面、估值及量化因子提供标准事实依据。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockMetricsDTO implements Serializable {

    /** 股票代码 (如 600519.SH) */
    private String stockCode;

    /** 股票简称 (如 贵州茅台) */
    private String stockName;

    /** 所属行业板块 */
    private String sector;

    /** 最新收盘价 / 市价 (元) */
    private BigDecimal latestPrice;

    /** 滚动市盈率 (PE-TTM) */
    private BigDecimal peTtm;

    /** 市净率 (PB) */
    private BigDecimal pb;

    /** 净资产收益率 (ROE, %) */
    private BigDecimal roe;

    /** 近12个月滚动股息率 (%) */
    private BigDecimal dividendYield;

    /** 总市值 (亿元) */
    private BigDecimal totalMarketCapBillion;

    /** 贝塔系数 (Beta, 相对沪深300) */
    private BigDecimal beta;

    /** 数据更新基准日期 */
    private LocalDate updateDate;
}
