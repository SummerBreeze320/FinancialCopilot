package com.financial.copilot.domain.stock.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <h1>股票标的领域实体</h1>
 * <p>
 * 封装上市公司的证券代码、简称、所属交易所、申万行业分类与市值规模等核心基本面属性。
 * 为投研平台的个股分析与行业轮动提供领域模型支撑。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockInfo {

    /** 股票代码 (如: 600519.SH, 000858.SZ) */
    private String stockCode;

    /** 股票简称 (如: 贵州茅台) */
    private String stockName;

    /** 所属交易所 (SSE, SZSE, BSE, HKEX, NYSE, NASDAQ) */
    private String exchange;

    /** 所属申万一级行业 */
    private String primarySector;

    /** 所属申万二级子行业 */
    private String secondarySector;

    /** 上市日期 */
    private LocalDate listingDate;

    /** 总市值 (单位: 亿元) */
    private BigDecimal totalMarketCapBillion;

    /** 流通市值 (单位: 亿元) */
    private BigDecimal floatMarketCapBillion;
}
