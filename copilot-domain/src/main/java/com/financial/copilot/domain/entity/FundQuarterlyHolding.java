package com.financial.copilot.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 基金季度前十大重仓明细实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundQuarterlyHolding implements Serializable {

    private Long id;
    private String fundCode;
    private String reportQuarter;               // 例如: '2024Q2'
    private Integer rankOrder;                  // 排名 1-10
    private String stockCode;
    private String stockName;
    private BigDecimal holdingRatio;            // 占基金净值比例 (%)
    private BigDecimal holdingSharesTenThousand;// 持股数量 (万股)
    private String holdingSector;               // 申万一级行业分类
}
