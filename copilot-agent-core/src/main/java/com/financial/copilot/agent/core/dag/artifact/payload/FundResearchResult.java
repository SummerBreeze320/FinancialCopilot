package com.financial.copilot.agent.core.dag.artifact.payload;

import java.util.Map;

/**
 * <h1>单基金/经理多维体检与定量指标分析载荷</h1>
 */
public record FundResearchResult(
    String fundCode,
    String managerName,
    double alpha,
    double sharpeRatio,
    double maxDrawdown,
    int recoveryDays,
    Map<String, Double> scoreMatrix,
    String qualitativeSummary
) {
    public FundResearchResult {
        scoreMatrix = scoreMatrix != null ? Map.copyOf(scoreMatrix) : Map.of();
    }
}
