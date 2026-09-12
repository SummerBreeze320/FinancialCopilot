package com.financial.copilot.agent.core.dag.planner.tool;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * <h1>指标语义检索工具 (MetricRAGTool)</h1>
 * <p>
 * 接收自然语言投资诉求或语义概念（如“三年稳健”、“低回撤”、“高夏普”、“进攻性”），
 * 检索并推荐严密的量化指标集合与默认筛选阈值。
 * </p>
 */
@Component
public class MetricRAGTool {

    public record MetricSearchResult(
            String query,
            List<String> matchedMetrics,
            Map<String, Object> recommendedThresholds,
            String explanation
    ) {}

    private static final Map<String, List<String>> CONCEPT_DICTIONARY = Map.of(
            "稳健", List.of("max_drawdown", "calmar_ratio", "annualized_volatility"),
            "回撤", List.of("max_drawdown", "calmar_ratio"),
            "抗跌", List.of("max_drawdown", "downside_deviation", "calmar_ratio"),
            "收益", List.of("annualized_return", "alpha", "excess_return"),
            "进攻", List.of("annualized_return", "alpha", "beta"),
            "性价比", List.of("sharpe_ratio", "sortino_ratio", "calmar_ratio"),
            "综合", List.of("sharpe_ratio", "annualized_return", "max_drawdown"),
            "规模", List.of("fund_scale", "turnover_rate")
    );

    /**
     * 根据自然语言查询检索指标
     */
    public MetricSearchResult searchMetrics(String query) {
        if (query == null || query.isBlank()) {
            return new MetricSearchResult(query, List.of("sharpe_ratio", "annualized_return"), Map.of(), "默认综合配置指标");
        }

        Set<String> matched = new LinkedHashSet<>();
        Map<String, Object> thresholds = new HashMap<>();

        for (Map.Entry<String, List<String>> entry : CONCEPT_DICTIONARY.entrySet()) {
            if (query.contains(entry.getKey())) {
                matched.addAll(entry.getValue());
            }
        }

        if (matched.isEmpty()) {
            matched.add("sharpe_ratio");
            matched.add("annualized_return");
            matched.add("max_drawdown");
        }

        if (matched.contains("max_drawdown")) {
            thresholds.put("maxDrawdownLimit", 15.0);
        }
        if (matched.contains("sharpe_ratio")) {
            thresholds.put("minSharpe", 1.0);
        }
        if (matched.contains("calmar_ratio")) {
            thresholds.put("minCalmar", 0.8);
        }

        return new MetricSearchResult(
                query,
                List.copyOf(matched),
                thresholds,
                "根据关键词匹配到的量化风控与收益指标"
        );
    }
}
