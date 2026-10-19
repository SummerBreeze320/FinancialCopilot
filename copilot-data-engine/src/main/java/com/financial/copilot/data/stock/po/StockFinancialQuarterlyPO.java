package com.financial.copilot.data.stock.po;

import com.baomidou.mybatisplus.annotation.IdType;
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
 * <h1>股票季度核心财务指标持久化对象 (Stock Financial Quarterly PO)</h1>
 * <p>
 * 对应数据库物理表: {@code stock_financial_quarterly}
 * 涵盖利润表、资产负债表与现金流量表的核心指标，为个股深度基本面分析提供数据支撑。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stock_financial_quarterly")
public class StockFinancialQuarterlyPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 股票代码 (如 600519.SH) */
    private String stockCode;

    /** 报告季度 (如 2024Q2) */
    private String reportQuarter;

    /** 报告期截止日 */
    private LocalDate reportDate;

    // ── 利润表核心 ──────────────────────────────────────────────

    /** 营业收入 (亿元) */
    private BigDecimal revenueBillion;

    /** 营收同比增长率 (%) */
    private BigDecimal revenueYoy;

    /** 归母净利润 (亿元) */
    private BigDecimal netProfitBillion;

    /** 归母净利润同比增长率 (%) */
    private BigDecimal netProfitYoy;

    /** 扣非归母净利润 (亿元) */
    private BigDecimal deductedNetProfitBillion;

    /** 基本每股收益 (元) */
    private BigDecimal eps;

    /** 稀释每股收益 (元) */
    private BigDecimal dilutedEps;

    // ── 盈利能力 ────────────────────────────────────────────────

    /** 销售毛利率 (%) */
    private BigDecimal grossMargin;

    /** 销售净利率 (%) */
    private BigDecimal netMargin;

    /** 加权净资产收益率 (%) */
    private BigDecimal roe;

    /** 总资产收益率 (%) */
    private BigDecimal roa;

    /** 投入资本回报率 (%) */
    private BigDecimal roic;

    // ── 资产负债表核心 ──────────────────────────────────────────

    /** 总资产 (亿元) */
    private BigDecimal totalAssetsBillion;

    /** 归母净资产 (亿元) */
    private BigDecimal netAssetsBillion;

    /** 资产负债率 (%) */
    private BigDecimal debtToAssetRatio;

    /** 流动比率 */
    private BigDecimal currentRatio;

    /** 速动比率 */
    private BigDecimal quickRatio;

    // ── 现金流核心 ──────────────────────────────────────────────

    /** 经营活动现金流净额 (亿元) */
    private BigDecimal operatingCashflowBillion;

    /** 自由现金流 (亿元) */
    private BigDecimal freeCashflowBillion;

    // ── 每股指标 ────────────────────────────────────────────────

    /** 每股净资产 (元) */
    private BigDecimal bps;

    /** 每股未分配利润 (元) */
    private BigDecimal undistributedEps;

    private LocalDateTime createdAt;
}
