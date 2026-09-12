package com.financial.copilot.agent.core.dag.artifact.payload;

import java.util.List;
import java.util.Map;

/**
 * <h1>决赛圈多基金深度横向对标报告载荷</h1>
 */
public record ComparisonReport(
    List<String> comparedFundCodes,
    String winnerFundCode,
    Map<String, Object> radarMetrics,
    String comparisonAnalysis,
    List<String> sharedHoldings
) {
    public ComparisonReport {
        comparedFundCodes = comparedFundCodes != null ? List.copyOf(comparedFundCodes) : List.of();
        radarMetrics = radarMetrics != null ? Map.copyOf(radarMetrics) : Map.of();
        sharedHoldings = sharedHoldings != null ? List.copyOf(sharedHoldings) : List.of();
    }

    public ComparisonReport(
            List<String> comparedFundCodes,
            String winnerFundCode,
            Map<String, Object> radarMetrics,
            String comparisonAnalysis
    ) {
        this(comparedFundCodes, winnerFundCode, radarMetrics, comparisonAnalysis, List.of());
    }

    public static ComparisonReport of(String fundCodeA, String fundCodeB, String analysis, List<String> sharedHoldings) {
        return new ComparisonReport(
                List.of(fundCodeA, fundCodeB),
                fundCodeA,
                Map.of(),
                analysis,
                sharedHoldings != null ? sharedHoldings : List.of()
        );
    }
}
