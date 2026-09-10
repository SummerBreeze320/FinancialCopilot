package com.financial.copilot.domain.fund.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * <h1>基金季度前十大重仓股票持仓明细领域实体</h1>
 * <p>
 * 穿透展现基金在指定季度季报披露的前十大股票仓位与所属申万一级行业板块，用于集中度与风格漂移分析。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundQuarterlyHolding {

    /** 自增主键 ID */
    private Long id;

    /** 基金标准代码 */
    private String fundCode;

    /** 报告期季度标识 (例如: "2024Q2") */
    private String reportQuarter;

    /** 重仓持股位次 (1 到 10) */
    private Integer rankOrder;

    /** 重仓股票代码 (例如: 600519) */
    private String stockCode;

    /** 重仓股票简称 (例如: 贵州茅台) */
    private String stockName;

    /** 占基金资产净值比例 (%) */
    private BigDecimal holdingRatio;

    /** 持股数量 (万股) */
    private BigDecimal holdingSharesTenThousand;

    /** 所属行业板块 (申万一级行业，例如: 食品饮料、医药生物) */
    private String holdingSector;
}
