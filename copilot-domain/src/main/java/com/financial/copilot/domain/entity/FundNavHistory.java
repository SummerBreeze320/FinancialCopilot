package com.financial.copilot.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 基金日度净值时序实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundNavHistory implements Serializable {

    private Long id;
    private String fundCode;
    private LocalDate navDate;
    private BigDecimal unitNav;
    private BigDecimal accumulatedNav;
    private BigDecimal adjustedNav;      // 复权净值 (分红再投资)
    private BigDecimal dailyGrowthRate;  // 日涨跌幅 (%)
}
