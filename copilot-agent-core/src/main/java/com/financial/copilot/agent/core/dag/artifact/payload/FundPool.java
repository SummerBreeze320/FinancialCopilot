package com.financial.copilot.agent.core.dag.artifact.payload;

import java.util.List;

/**
 * <h1>基金初筛标的池业务载荷</h1>
 */
public record FundPool(
    List<String> fundCodes,
    String screeningReason,
    int totalCount
) {
    public FundPool {
        fundCodes = fundCodes != null ? List.copyOf(fundCodes) : List.of();
    }
}
