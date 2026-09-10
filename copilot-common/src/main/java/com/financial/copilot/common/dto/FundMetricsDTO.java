package com.financial.copilot.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 基金量化多维度健康体检指标 DTO
 * 由 copilot-math-core 计算产出，作为 Agent 对标与报告的权威只读事实源。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundMetricsDTO implements Serializable {

    private String fundCode;
    private String fundName;
    private String fundType;
    private LocalDate startDate;
    private LocalDate endDate;

    // 收益特征
    private BigDecimal cumulativeReturn;    // 区间累计回报率 (%)
    private BigDecimal annualizedReturn;    // 区间年化复合回报率 (%)

    // 风险与下行控制
    private BigDecimal maxDrawdown;          // 最大回撤 (%)
    private BigDecimal annualizedVolatility; // 年化波动率 (%)

    // 风险调整收益
    private BigDecimal sharpeRatio;          // 夏普比率
    private BigDecimal calmarRatio;          // 卡玛比率

    // 持仓结构穿透特征
    private BigDecimal top10Concentration;   // 前十大持仓集中度 (%)
    private String primarySector;            // 第一大权重配置行业
    private BigDecimal primarySectorRatio;   // 第一大行业占比 (%)
}
