package com.financial.copilot.agent.core.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <h1>工具观察结果净化器 (Observation Sanitizer)</h1>
 * <p>
 * 职责：遵循 Context Engineering 最佳实践，将底层数据工具返回的大段原始 JSON、
 * 冗余 null 字段及低价值嵌套对象，转化为模型极易理解的高信噪比事实文本。
 * 显著降低 Token 消耗（通常降低 50%~75%），消除模型在长 JSON 括号中的注意力分散。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class ObservationSanitizer {

    private final ObjectMapper objectMapper;

    public ObservationSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 净化基金量化指标 JSON 为紧凑结构化事实
     *
     * @param rawMetricsJson 原始 FundMetricsDTO JSON
     * @return 高信噪比 Markdown 摘要文本
     */
    public String sanitizeFundMetrics(String rawMetricsJson) {
        if (rawMetricsJson == null || rawMetricsJson.isBlank()) {
            return "暂无权威量化指标披露";
        }
        try {
            JsonNode node = objectMapper.readTree(rawMetricsJson);
            if (node.has("error")) {
                return "指标数据采集异常: " + node.get("error").asText();
            }

            StringBuilder sb = new StringBuilder();
            String code = node.path("fundCode").asText("未知代码");
            String name = node.path("fundName").asText("未知基金");
            String type = node.path("fundType").asText("公募基金");
            sb.append("- 标的基本信息: ").append(name).append(" (代码: ").append(code).append(", 类型: ").append(type).append(")\n");

            List<String> metricFacts = new ArrayList<>();
            appendDecimal(node, "annualizedReturn", "年化收益率", "%", metricFacts);
            appendDecimal(node, "cumulativeReturn", "区间累计收益", "%", metricFacts);
            appendDecimal(node, "maxDrawdown", "区间最大回撤", "%", metricFacts);
            appendDecimal(node, "annualizedVolatility", "年化波动率", "%", metricFacts);
            appendDecimal(node, "sharpeRatio", "夏普比率(超额回报/总风险)", "", metricFacts);
            appendDecimal(node, "calmarRatio", "卡玛比率(年化/最大回撤)", "", metricFacts);
            appendDecimal(node, "top10Concentration", "前十大持仓集中度", "%", metricFacts);

            if (node.hasNonNull("primarySector")) {
                String sector = node.path("primarySector").asText();
                String ratio = node.hasNonNull("primarySectorRatio") ? " (" + node.path("primarySectorRatio").asText() + "%)" : "";
                metricFacts.add("第一重仓行业: " + sector + ratio);
            }

            if (!metricFacts.isEmpty()) {
                sb.append("- 核心量化指标: ").append(String.join(" | ", metricFacts));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("[SANITIZER] 基金量化指标净化降级: raw={}, err={}", rawMetricsJson, e.getMessage());
            return rawMetricsJson;
        }
    }

    /**
     * 净化基金季度前十大重仓股票持仓 JSON 为精炼有序列表
     *
     * @param rawHoldingsJson 原始 List<FundQuarterlyHolding> JSON
     * @return 紧凑持仓穿透事实文本
     */
    public String sanitizeHoldings(String rawHoldingsJson) {
        if (rawHoldingsJson == null || rawHoldingsJson.isBlank()) {
            return "暂无重仓持股穿透数据";
        }
        try {
            JsonNode root = objectMapper.readTree(rawHoldingsJson);
            if (root.isArray() && root.isEmpty()) {
                return "报告期内无重仓持股明细";
            }
            if (root.has("error")) {
                return "持仓查询异常: " + root.get("error").asText();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("- 前十大重仓股票穿透:\n");
            int count = 0;
            BigDecimal totalRatio = BigDecimal.ZERO;

            for (JsonNode item : root) {
                count++;
                String stockName = item.path("stockName").asText("未知股票");
                String stockCode = item.path("stockCode").asText("");
                String sector = item.path("holdingSector").asText("行业未披露");
                BigDecimal ratio = item.hasNonNull("holdingRatio") ? new BigDecimal(item.path("holdingRatio").asText()) : BigDecimal.ZERO;
                totalRatio = totalRatio.add(ratio);

                sb.append("  ").append(count).append(". ")
                  .append(stockName).append(" (").append(stockCode).append("): 占比 ")
                  .append(ratio).append("%, 所属板块: ").append(sector).append("\n");
                if (count >= 10) break;
            }

            if (totalRatio.compareTo(BigDecimal.ZERO) > 0) {
                sb.append("  * 前十大持仓合计净值占比: ").append(totalRatio).append("%");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("[SANITIZER] 持仓明细净化降级: err={}", e.getMessage());
            return rawHoldingsJson;
        }
    }

    /**
     * 净化定性定期报告策略观点（过滤噪声与过长空白，按字符预算截断）
     *
     * @param rawReportView 原始季报策略观点
     * @param maxChars      最大字符预算 (建议 1000 字符以内)
     * @return 规整后的定性观点事实
     */
    public String sanitizeReportView(String rawReportView, int maxChars) {
        if (rawReportView == null || rawReportView.isBlank()) {
            return "暂无季报定性策略展望观点披露";
        }
        String cleaned = rawReportView.trim().replaceAll("[\\t\\r]+", "").replaceAll("\n{3,}", "\n\n");
        if (maxChars > 0 && cleaned.length() > maxChars) {
            return cleaned.substring(0, maxChars) + "\n...[超长定性内容已做上下文预算裁剪]...";
        }
        return cleaned;
    }

    /**
     * 净化知识图谱重仓重合度分析 JSON
     *
     * @param rawOverlapJson 原始图谱重合分析 JSON
     * @return 紧凑高信噪比事实说明
     */
    public String sanitizeGraphOverlap(String rawOverlapJson) {
        if (rawOverlapJson == null || rawOverlapJson.isBlank()) {
            return "暂无知识图谱持仓重合度分析";
        }
        try {
            JsonNode node = objectMapper.readTree(rawOverlapJson);
            if (node.has("error")) {
                return "图谱分析提示: " + node.get("error").asText();
            }
            int overlapCount = node.path("overlapCount").asInt(0);
            JsonNode shared = node.path("sharedStockCodes");
            List<String> codes = new ArrayList<>();
            if (shared != null && shared.isArray()) {
                for (JsonNode c : shared) {
                    codes.add(c.asText());
                }
            }
            if (overlapCount == 0) {
                return "- 知识图谱穿透: 双基金前十大重仓股无重合，持仓相关性与风险重叠度极低。";
            } else {
                return "- 知识图谱穿透: 双基金共同重仓 " + overlapCount + " 只股票 (" + String.join(", ", codes) + ")，存在一定持仓重叠与协同风险。";
            }
        } catch (Exception e) {
            log.warn("[SANITIZER] 图谱重合度净化降级: err={}", e.getMessage());
            return rawOverlapJson;
        }
    }

    private void appendDecimal(JsonNode node, String fieldName, String label, String suffix, List<String> list) {
        if (node.hasNonNull(fieldName)) {
            String val = node.path(fieldName).asText();
            list.add(label + " " + val + suffix);
        }
    }
}
