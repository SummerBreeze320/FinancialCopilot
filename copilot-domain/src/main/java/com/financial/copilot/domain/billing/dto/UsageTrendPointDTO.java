package com.financial.copilot.domain.billing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * <h1>Token 消耗时序统计点传输对象 (Usage Trend Point DTO)</h1>
 * <p>
 * 用于前端绘制过去 7 天或 30 天每日消耗 Token 数量与算力点数趋势折线图/面积图。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageTrendPointDTO implements Serializable {

    /**
     * 统计日期 (格式: YYYY-MM-DD)
     */
    private String statDate;

    /**
     * 当日总消耗 Token 数量
     */
    private Long totalTokens;

    /**
     * 当日总扣减算力点数
     */
    private Long consumedPoints;

    /**
     * 当日发起的投研任务总频次
     */
    private Long requestCount;
}
