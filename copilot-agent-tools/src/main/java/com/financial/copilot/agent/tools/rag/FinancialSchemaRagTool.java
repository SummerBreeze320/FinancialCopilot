package com.financial.copilot.agent.tools.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.shared.rag.entity.RagFundMetric;
import com.financial.copilot.domain.shared.rag.entity.RagFundSector;
import com.financial.copilot.domain.shared.rag.entity.SchemaRecallResult;
import com.financial.copilot.domain.shared.rag.port.RagEmbeddingPort;
import com.financial.copilot.domain.shared.rag.port.RagGraphPort;
import com.financial.copilot.domain.shared.rag.port.RagSchemaPort;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * <h1>金融指标与板块知识库混合 RAG 工具 (Financial Schema RAG Tool)</h1>
 * <p>
 * 遵循 Tool-as-Truth 契约规范，为上层金融 Copilot Agent (Text-to-SQL / DSL 查询生成 / 金融知识问答) 提供：
 * <ul>
 *   <li>指标与分类板块的语义/词面/精确代码三路混合联合召回；</li>
 *   <li>指标投资释义解析与基于 Neo4j 知识图谱的同族关联指标推荐；</li>
 *   <li>多层级板块树向下穿透展开，将非叶子板块递归解包为具体可交易的底层叶子板块编码列表。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FinancialSchemaRagTool {

    private final RagEmbeddingPort embeddingPort;
    private final RagSchemaPort schemaPort;
    private final Optional<RagGraphPort> graphPort;
    private final ObjectMapper objectMapper;

    @Autowired
    public FinancialSchemaRagTool(
            RagEmbeddingPort embeddingPort,
            RagSchemaPort schemaPort,
            Optional<RagGraphPort> graphPort,
            ObjectMapper objectMapper
    ) {
        this.embeddingPort = embeddingPort;
        this.schemaPort = schemaPort;
        this.graphPort = graphPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据用户自然语言查询，联合召回匹配的公募基金指标和分类板块
     *
     * @param userQuery 用户自然语言（如 "近1年收益大于20%的消费行业ETF"）
     * @param topK      每类最多召回数量，默认 5
     * @return 结构化 JSON 字符串
     */
    @Tool(name = "match_metrics_and_sectors", description = "基于向量与全文三路检索自然语言匹配金融指标助记码与分类板块", readOnly = true)
    public String matchMetricsAndSectors(
            @ToolParam(name = "userQuery", description = "用户自然语言诉求，例如'近1年收益大于20%的消费行业ETF'") String userQuery,
            @ToolParam(name = "topK", description = "每类最多召回数量，默认 5", required = false) Integer topK) {
        log.info("[TOOL CALL-RAG] 联合召回指标与板块: query={}, topK={}", userQuery, topK);
        try {
            int k = (topK == null || topK <= 0) ? 5 : topK;

            // 1. 生成 1024 维查询向量
            float[] queryVector = embeddingPort.embed(userQuery);

            // 2. PG 混合检索 (精确/别名 + Trigram + 向量)
            List<SchemaRecallResult.MetricMatch> metricMatches = schemaPort.searchMetrics(userQuery, queryVector, k);
            List<SchemaRecallResult.SectorMatch> sectorMatches = schemaPort.searchSectors(userQuery, queryVector, k);

            // 3. 构建指标结果
            List<Map<String, Object>> metricsList = new ArrayList<>();
            for (SchemaRecallResult.MetricMatch mm : metricMatches) {
                RagFundMetric m = mm.metric();
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("mnemonic", m.getMnemonic());
                map.put("name", m.getIndexName());
                map.put("category", m.getParentName());
                map.put("description", m.getDescription());
                map.put("score", mm.score());
                map.put("supportedUsage", m.getSupportedUsage());
                metricsList.add(map);
            }

            // 4. 构建板块结果（若是父级板块，则借助图数据库展开所有底层叶子板块 ID）
            List<Map<String, Object>> sectorsList = new ArrayList<>();
            for (SchemaRecallResult.SectorMatch sm : sectorMatches) {
                RagFundSector s = sm.sector();
                List<String> leafIds = sm.expandedLeafIds();

                if ((leafIds == null || leafIds.isEmpty()) && !s.isLeaf() && graphPort.isPresent()) {
                    try {
                        leafIds = graphPort.get().expandLeafSectors(s.getSectorId());
                    } catch (Exception e) {
                        log.warn("Neo4j 展开板块 {} 叶子节点失败: {}", s.getSectorId(), e.getMessage());
                    }
                }

                if (leafIds == null || leafIds.isEmpty()) {
                    leafIds = List.of(s.getSectorId());
                }

                Map<String, Object> map = new LinkedHashMap<>();
                map.put("sectorId", s.getSectorId());
                map.put("name", s.getName());
                map.put("fullPath", s.getFullPathNames());
                map.put("score", sm.score());
                map.put("isLeaf", s.isLeaf());
                map.put("expandedLeafIds", leafIds);
                sectorsList.add(map);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", userQuery);
            result.put("matchedMetrics", metricsList);
            result.put("matchedSectors", sectorsList);

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("联合召回指标与板块失败: query={}", userQuery, e);
            return "{\"error\": \"联合召回失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 解释指标定义、计算公式，并从知识图谱获取同分类推荐指标
     *
     * @param metricMnemonicOrName 指标助记符（如 f_return_1y）或指标名称（如 近1年回报）
     * @return 结构化 JSON 字符串
     */
    @Tool(name = "explain_metric", description = "基于指标知识库与Neo4j知识图谱解释金融指标定义与计算公式，并推荐同类衍生指标", readOnly = true)
    public String explainMetric(
            @ToolParam(name = "metricMnemonicOrName", description = "指标助记符（如 f_return_1y）或指标名称（如 近1年回报）") String metricMnemonicOrName) {
        log.info("[TOOL CALL-RAG] 解释指标及推荐同类: metric={}", metricMnemonicOrName);
        try {
            Optional<RagFundMetric> optMetric = schemaPort.findMetricByMnemonic(metricMnemonicOrName);
            if (optMetric.isEmpty()) {
                List<SchemaRecallResult.MetricMatch> matches = schemaPort.searchMetrics(metricMnemonicOrName, null, 1);
                if (!matches.isEmpty()) {
                    optMetric = Optional.ofNullable(matches.get(0).metric());
                }
            }

            if (optMetric.isEmpty()) {
                return objectMapper.writeValueAsString(Map.of(
                        "error", "未找到指定指标: " + metricMnemonicOrName
                ));
            }

            RagFundMetric metric = optMetric.get();
            List<String> siblings = List.of();
            if (graphPort.isPresent()) {
                try {
                    siblings = graphPort.get().findMetricSiblings(metric.getMnemonic(), 5);
                } catch (Exception e) {
                    log.warn("Neo4j 获取同族指标失败: metric={}, error={}", metric.getMnemonic(), e.getMessage());
                }
            }

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("mnemonic", metric.getMnemonic());
            map.put("indexName", metric.getIndexName());
            map.put("parentName", metric.getParentName());
            map.put("description", metric.getDescription());
            map.put("supportedUsage", metric.getSupportedUsage());
            map.put("applicableProducts", metric.getApplicableProducts());
            map.put("siblingMetrics", siblings);

            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.error("解释指标失败: metric={}", metricMnemonicOrName, e);
            return "{\"error\": \"指标解释失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 展开指定板块树，向下穿透至底层所有叶子板块 ID，供筛选器或 SQL IN 查询使用
     *
     * @param sectorIdOrName 板块ID（如 1000009160000000）或板块名称
     * @return 结构化 JSON 字符串
     */
    @Tool(name = "expand_sector", description = "基于Neo4j板块分类树向下递归展开底层所有叶子板块编码列表", readOnly = true)
    public String expandSector(
            @ToolParam(name = "sectorIdOrName", description = "板块ID（如 1000009160000000）或板块名称，支持模糊检索板块") String sectorIdOrName) {
        log.info("[TOOL CALL-RAG] 展开板块叶子节点: sector={}", sectorIdOrName);
        try {
            String targetSectorId = sectorIdOrName;
            String sectorName = null;

            if (!targetSectorId.matches("^\\d{10,}$")) {
                List<SchemaRecallResult.SectorMatch> matches = schemaPort.searchSectors(sectorIdOrName, null, 1);
                if (!matches.isEmpty()) {
                    targetSectorId = matches.get(0).sector().getSectorId();
                    sectorName = matches.get(0).sector().getName();
                }
            }

            List<String> leafIds = List.of();
            if (graphPort.isPresent()) {
                try {
                    leafIds = graphPort.get().expandLeafSectors(targetSectorId);
                } catch (Exception e) {
                    log.warn("Neo4j 展开板块失败: sectorId={}, error={}", targetSectorId, e.getMessage());
                }
            }

            if (leafIds.isEmpty()) {
                leafIds = List.of(targetSectorId);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sectorId", targetSectorId);
            if (sectorName != null) {
                result.put("sectorName", sectorName);
            }
            result.put("expandedLeafIds", leafIds);
            result.put("leafCount", leafIds.size());

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("展开板块失败: sector={}", sectorIdOrName, e);
            return "{\"error\": \"展开板块失败: " + e.getMessage() + "\"}";
        }
    }
}
