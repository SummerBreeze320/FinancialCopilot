package com.financial.copilot.agent.core.dag.artifact.payload;

import java.util.Map;

/**
 * <h1>宏观流动性与基准走势观点载荷</h1>
 */
public record MacroResearchResult(
    String interestRateTrend,
    String monetaryPolicyTone,
    Map<String, Double> benchmarkReturns,
    String summary
) {
    public MacroResearchResult {
        benchmarkReturns = benchmarkReturns != null ? Map.copyOf(benchmarkReturns) : Map.of();
    }
}
