package com.financial.copilot.agent.core.infra.dag.planner.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.tools.rag.FinancialSchemaRagTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * <h1>指标语义检索工具 (MetricRAGTool)</h1>
 * <p>
 * 接收自然语言投资诉求或语义概念（如“三年稳健”、“低回撤”、“高夏普”、“进攻性”），
 * 优先调用基于 pgvector 与 Neo4j 的真实金融 RAG 工具 {@link FinancialSchemaRagTool}，
 * 在 RAG 离线时无缝回退到基于规则的内存概念词典，检索并推荐量化指标集合与默认筛选阈值。
 * </p>
 */
@Slf4j
@Component
public class MetricRAGTool {

    public record MetricSearchResult(
            String query,
            List<String> matchedMetrics,
            Map<String, Object> recommendedThresholds,
            String explanation
    ) {}

    private final FinancialSchemaRagTool schemaRagTool;
    private final ObjectMapper objectMapper;

    public MetricRAGTool() {
        this(null, new ObjectMapper());
    }

    @Autowired
    public MetricRAGTool(
            @Autowired(required = false) FinancialSchemaRagTool schemaRagTool,
            ObjectMapper objectMapper
    ) {
        this.schemaRagTool = schemaRagTool;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
    }

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

        // 1. 优先尝试通过 FinancialSchemaRagTool 进行三路混合检索召回
        if (schemaRagTool != null) {
            try {
                String ragJson = schemaRagTool.matchMetricsAndSectors(query, 5);
                JsonNode root = objectMapper.readTree(ragJson);
                JsonNode metricsNode = root.path("matchedMetrics");
                if (metricsNode.isArray() && !metricsNode.isEmpty()) {
                    for (JsonNode m : metricsNode) {
                        String mnemonic = m.path("mnemonic").asText(null);
                        if (mnemonic != null && !mnemonic.isBlank()) {
                            matched.add(mnemonic);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[MetricRAGTool] 调用 FinancialSchemaRagTool 失败，降级回退内存词典: query={}, err={}",
                        query, e.getMessage());
            }
        }

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
