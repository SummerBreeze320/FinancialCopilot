package com.financial.copilot.data.stock.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <h1>股票基础信息持久化实体 PO (Stock Info PO)</h1>
 * <p>
 * 对应数据库物理表: {@code stock_info}
 * 遵循 Lombok + MyBatis-Plus 规范，支持个股估值、行业与基础财务指标映射。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stock_info")
public class StockInfoPO {

    /**
     * 股票代码（主键，例如 "600519.SH"、"000858.SZ"）
     */
    @TableId
    private String stockCode;

    /**
     * 股票简称（例如 "贵州茅台"、"五粮液"）
     */
    private String stockName;

    /**
     * 所属交易所（SSE 上海证券交易所 / SZSE 深圳证券交易所 / BSE 北京证券交易所）
     */
    private String exchange;

    /**
     * 所属申万一级行业（例如 "食品饮料"、"医药生物"、"电子"）
     */
    private String primarySector;

    /**
     * 所属申万二级子行业
     */
    private String secondarySector;

    /**
     * 上市日期
     */
    private LocalDate listingDate;

    /**
     * 滚动市盈率 (PE-TTM)
     */
    private BigDecimal peTtm;

    /**
     * 市净率 (PB)
     */
    private BigDecimal pb;

    /**
     * 总市值（亿元）
     */
    private BigDecimal totalMarketCapBillion;

    /**
     * 流通市值（亿元）
     */
    private BigDecimal floatMarketCapBillion;

    /**
     * 净资产收益率 ROE (%)
     */
    private BigDecimal roe;

    /**
     * 近 12 个月股息率 (%)
     */
    private BigDecimal dividendYield;

    /**
     * 记录创建与更新时间
     */
    private LocalDateTime createdAt;
}
