package com.financial.copilot.domain.fund.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <h1>基金历史每日复权净值时序领域实体</h1>
 * <p>
 * 记录单只基金在每个交易日的官方估值事实，用于收益率、最大回撤及年化波动率的高精度纯数学计算。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundNavHistory {

    /** 自增主键 ID */
    private Long id;

    /** 基金标准代码 */
    private String fundCode;

    /** 估值净值日期 */
    private LocalDate navDate;

    /** 单位净值 (元) */
    private BigDecimal unitNav;

    /** 累计净值 (元) */
    private BigDecimal accumulatedNav;

    /** 复权净值 (元，分红再投资口径，指标计算核心基准) */
    private BigDecimal adjustedNav;

    /** 当日净值涨跌幅 (%) */
    private BigDecimal dailyGrowthRate;
}
