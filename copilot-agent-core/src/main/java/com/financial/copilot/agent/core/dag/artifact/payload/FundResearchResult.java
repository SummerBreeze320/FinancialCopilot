package com.financial.copilot.agent.core.dag.artifact.payload;

import java.util.List;
import java.util.Map;

/**
 * <h1>单基金/批量基金经理多维体检与定量指标分析载荷</h1>
 */
public record FundResearchResult(
    String fundCode,
    String managerName,
    double alpha,
    double sharpeRatio,
    double maxDrawdown,
    int recoveryDays,
    Map<String, Double> scoreMatrix,
    String qualitativeSummary,
    List<Map<String, Object>> evaluatedFunds,
    List<String> topCandidates
) {
    public FundResearchResult {
        scoreMatrix = scoreMatrix != null ? Map.copyOf(scoreMatrix) : Map.of();
        evaluatedFunds = evaluatedFunds != null ? List.copyOf(evaluatedFunds) : List.of();
        topCandidates = topCandidates != null ? List.copyOf(topCandidates) : List.of();
    }

    public FundResearchResult(
            String fundCode,
            String managerName,
            double alpha,
            double sharpeRatio,
            double maxDrawdown,
            int recoveryDays,
            Map<String, Double> scoreMatrix,
            String qualitativeSummary
    ) {
        this(fundCode, managerName, alpha, sharpeRatio, maxDrawdown, recoveryDays, scoreMatrix, qualitativeSummary, List.of(), List.of());
    }

    public static FundResearchResult ofBatch(List<Map<String, Object>> evaluatedFunds, List<String> topCandidates) {
        String bestCode = (topCandidates != null && !topCandidates.isEmpty()) ? topCandidates.get(0) : "";
        return new FundResearchResult(
                bestCode,
                "",
                0.0,
                0.0,
                0.0,
                0,
                Map.of(),
                "批量多维量化体检完成，筛选出最优标的: " + topCandidates,
                evaluatedFunds,
                topCandidates
        );
    }
}
