package com.financial.copilot.domain.billing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * <h1>Token 扣费结算结果传输对象 (Token Deduction Result)</h1>
 * <p>
 * 供研报底部“消耗透明度仪表”渲染：包含实际调用厂商、模型、Token 数、扣减点数、耗时与剩余点数。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenDeductionResult implements Serializable {

    private boolean success;

    private String provider;

    private String model;

    private int promptTokens;

    private int completionTokens;

    private int totalTokens;

    private long consumedPoints;

    private double estimatedCostCny;

    private long remainingBalancePoints;

    private long latencyMs;

    private String message;
}
