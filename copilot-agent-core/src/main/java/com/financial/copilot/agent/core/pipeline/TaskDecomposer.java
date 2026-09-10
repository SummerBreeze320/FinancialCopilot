package com.financial.copilot.agent.core.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.service.DeepSeekClientService;
import com.financial.copilot.common.enums.AssetCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h1>复合投研任务解构器 (Task Decomposer)</h1>
 * <p>
 * 负责分析用户的自然语言指令，识别目标资产大类（公募基金 FUND、股票 STOCK、期货 FUTURES、银行理财 WEALTH），
 * 判断属于单意图直达还是多步复合流水线，并将其智能解构为带拓扑依赖的 {@link ExecutionPlan}。
 * 具备 DeepSeek 大模型深度规划与离线规则引擎双重兜底能力。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class TaskDecomposer {

    private final DeepSeekClientService deepSeekClient;
    private final ObjectMapper objectMapper;

    private static final String DECOMPOSER_PROMPT = """
        你是一个资深金融智能投研规划专家。你的任务是分析用户的自然语言指令，判断其资产大类与任务复杂度，并输出规范的 JSON 格式执行计划 (ExecutionPlan)。
        
        支持的资产大类 (assetCategory):
        - FUND: 公募基金产品及基金经理 (首期深度实施)
        - STOCK: 股票上市公司标的 (规划中)
        - FUTURES: 大宗期货及衍生品 (规划中)
        - WEALTH_MANAGEMENT: 银行理财及信托 (规划中)
        
        支持的子任务类型 (taskType):
        1. SCREENING: 标的初筛，根据板块、年限、最大回撤、夏普等条件过滤候选标的。
        2. BATCH_ANALYSIS: 对前 N 名标的或基金经理进行多维量化体检与综合评分。
        3. COMPARISON: 对比前序选出的最终标的 (如 Top 2) 的定量指标与季报定性投资策略展望。
        4. SYNTHESIS: 综合汇总前序所有事实数据，生成包含资产配置比例、风险收益比及合规提示的最终投资建议报告。
        
        【输出格式要求】:
        必须且仅输出合法的 JSON 字符串，格式如下：
        {
          "assetCategory": "FUND",
          "isComplex": true,
          "summary": "简述整个任务流水线目标",
          "steps": [
            {
              "stepIndex": 1,
              "taskType": "SCREENING",
              "description": "筛选过去三年表现稳定的医药基金",
              "dependsOn": [],
              "outputKey": "candidateFunds",
              "params": {"sector": "医药", "minYears": 3, "limit": 10}
            },
            {
              "stepIndex": 2,
              "taskType": "BATCH_ANALYSIS",
              "description": "评估候选基金排名前5的基金经理专业能力并打分",
              "dependsOn": [1],
              "inputKey": "candidateFunds",
              "outputKey": "topCandidates",
              "params": {"topN": 5, "selectBest": 2}
            },
            {
              "stepIndex": 3,
              "taskType": "COMPARISON",
              "description": "对排名前2的最优标的进行全方位定量与定性对标",
              "dependsOn": [2],
              "inputKey": "topCandidates",
              "outputKey": "comparisonFacts",
              "params": {}
            },
            {
              "stepIndex": 4,
              "taskType": "SYNTHESIS",
              "description": "汇总前序事实，生成包含配置逻辑与风险提示的最终投资建议",
              "dependsOn": [1, 2, 3],
              "inputKey": "comparisonFacts",
              "outputKey": "finalReport",
              "params": {}
            }
          ]
        }
        
        如果是简单单意图请求（如仅筛选、仅查询单只基金或仅对比指定的两只基金），isComplex 为 false，steps 数组只包含 1 个步骤。
        """;

    public TaskDecomposer(DeepSeekClientService deepSeekClient, ObjectMapper objectMapper) {
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 将用户自然语言诉求解构为有序 ExecutionPlan
     *
     * @param userQuery 用户的投研指令文本
     * @return 结构化的任务执行计划
     */
    public ExecutionPlan decompose(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return fallbackSingleTask(AssetCategory.FUND, "SCREENING", "默认展示优质公募基金标的");
        }

        try {
            String llmResponse = deepSeekClient.chat(DECOMPOSER_PROMPT, userQuery);
            ExecutionPlan plan = parseJsonPlan(llmResponse, userQuery);
            if (plan != null && !plan.getSteps().isEmpty()) {
                log.info("[TaskDecomposer] 成功解构任务: category={}, isComplex={}, steps={}, summary={}",
                        plan.getAssetCategory(), plan.isComplex(), plan.getSteps().size(), plan.getSummary());
                return plan;
            }
        } catch (Exception e) {
            log.warn("[TaskDecomposer] 模型解构异常，启用智能规则解构器: {}", e.getMessage());
        }

        return ruleBasedDecompose(userQuery);
    }

    private ExecutionPlan parseJsonPlan(String jsonContent, String originalQuery) {
        try {
            int start = jsonContent.indexOf("{");
            int end = jsonContent.lastIndexOf("}");
            if (start >= 0 && end > start) {
                String cleanJson = jsonContent.substring(start, end + 1);
                JsonNode root = objectMapper.readTree(cleanJson);

                AssetCategory category = detectCategory(root.path("assetCategory").asText("FUND"), originalQuery);
                boolean isComplex = root.path("isComplex").asBoolean(false);
                String summary = root.path("summary").asText("投研任务执行计划");
                List<SubTask> steps = new ArrayList<>();

                JsonNode stepsNode = root.path("steps");
                if (stepsNode.isArray()) {
                    for (JsonNode sn : stepsNode) {
                        int stepIndex = sn.path("stepIndex").asInt(steps.size() + 1);
                        String taskType = sn.path("taskType").asText("SCREENING");
                        String description = sn.path("description").asText("");
                        String inputKey = sn.has("inputKey") ? sn.path("inputKey").asText() : null;
                        String outputKey = sn.has("outputKey") ? sn.path("outputKey").asText() : null;

                        List<Integer> dependsOn = new ArrayList<>();
                        JsonNode depsNode = sn.path("dependsOn");
                        if (depsNode.isArray()) {
                            for (JsonNode d : depsNode) {
                                dependsOn.add(d.asInt());
                            }
                        }

                        Map<String, Object> params = new HashMap<>();
                        JsonNode paramsNode = sn.path("params");
                        if (paramsNode.isObject()) {
                            paramsNode.fields().forEachRemaining(entry -> {
                                if (entry.getValue().isNumber()) {
                                    params.put(entry.getKey(), entry.getValue().numberValue());
                                } else if (entry.getValue().isBoolean()) {
                                    params.put(entry.getKey(), entry.getValue().asBoolean());
                                } else {
                                    params.put(entry.getKey(), entry.getValue().asText());
                                }
                            });
                        }

                        steps.add(SubTask.builder()
                                .stepIndex(stepIndex)
                                .taskType(taskType)
                                .description(description)
                                .dependsOn(dependsOn)
                                .inputKey(inputKey)
                                .outputKey(outputKey)
                                .params(params)
                                .build());
                    }
                }

                if (!steps.isEmpty()) {
                    return ExecutionPlan.builder()
                            .assetCategory(category)
                            .isComplex(isComplex)
                            .summary(summary)
                            .steps(steps)
                            .build();
                }
            }
        } catch (Exception e) {
            log.debug("JSON 解析失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 规则解构兜底（当模型不可用或离线环境时精准匹配）
     */
    public ExecutionPlan ruleBasedDecompose(String query) {
        AssetCategory category = detectCategory(null, query);
        boolean hasChainKeywords = query.contains("然后") || query.contains("再") || query.contains("最后") || query.contains("接着");
        boolean hasComparison = query.contains("对比") || query.contains("比较");
        boolean hasAnalysis = query.contains("分析") || query.contains("体检") || query.contains("评估");
        boolean hasScreening = query.contains("筛选") || query.contains("找") || query.contains("选");
        boolean hasAdvice = query.contains("投资建议") || query.contains("配置") || query.contains("方案");

        if (hasChainKeywords || (hasScreening && hasAnalysis && hasComparison) || (hasScreening && hasAdvice)) {
            String sector = extractSector(query);

            List<SubTask> steps = List.of(
                    SubTask.builder()
                            .stepIndex(1)
                            .taskType("SCREENING")
                            .description("筛选过去三年表现稳定的" + sector + "主题基金")
                            .dependsOn(List.of())
                            .outputKey(ResearchBlackboard.KEY_CANDIDATE_FUNDS)
                            .params(Map.of("sector", sector, "minYears", 3, "limit", 10))
                            .build(),
                    SubTask.builder()
                            .stepIndex(2)
                            .taskType("BATCH_ANALYSIS")
                            .description("评估候选基金排名前5的基金经理专业能力并打分排序")
                            .dependsOn(List.of(1))
                            .inputKey(ResearchBlackboard.KEY_CANDIDATE_FUNDS)
                            .outputKey(ResearchBlackboard.KEY_TOP_CANDIDATES)
                            .params(Map.of("topN", 5, "selectBest", 2))
                            .build(),
                    SubTask.builder()
                            .stepIndex(3)
                            .taskType("COMPARISON")
                            .description("对排名前2的最优经理代表作展开多维定量与季报定性观点对标")
                            .dependsOn(List.of(2))
                            .inputKey(ResearchBlackboard.KEY_TOP_CANDIDATES)
                            .outputKey(ResearchBlackboard.KEY_COMPARISON_FACTS)
                            .params(Map.of())
                            .build(),
                    SubTask.builder()
                            .stepIndex(4)
                            .taskType("SYNTHESIS")
                            .description("综合汇总全流程量化事实与对标结论，生成投资建议报告")
                            .dependsOn(List.of(1, 2, 3))
                            .inputKey(ResearchBlackboard.KEY_COMPARISON_FACTS)
                            .outputKey(ResearchBlackboard.KEY_FINAL_REPORT)
                            .params(Map.of())
                            .build()
            );

            return ExecutionPlan.builder()
                    .assetCategory(category)
                    .isComplex(true)
                    .summary(sector + "基金筛选 -> 经理能力体检 -> Top2深度对标 -> 投资配置建议")
                    .steps(steps)
                    .build();
        }

        // 单意图退化处理
        if (hasComparison) {
            return fallbackSingleTask(category, "COMPARISON", "横向对比两只目标标的");
        } else if (hasAnalysis) {
            return fallbackSingleTask(category, "BATCH_ANALYSIS", "深度透视分析单只标的或投资经理");
        } else {
            return fallbackSingleTask(category, "SCREENING", "按多维指标筛选优质金融标的");
        }
    }

    private AssetCategory detectCategory(String rawCategory, String query) {
        if (rawCategory != null) {
            try {
                return AssetCategory.valueOf(rawCategory.trim().toUpperCase());
            } catch (Exception ignored) {
            }
        }
        if (query.contains("股票") || query.contains("A股") || query.contains("港股") || query.contains("美股")) {
            return AssetCategory.STOCK;
        }
        if (query.contains("期货") || query.contains("大宗商品")) {
            return AssetCategory.FUTURES;
        }
        if (query.contains("理财") || query.contains("信托")) {
            return AssetCategory.WEALTH_MANAGEMENT;
        }
        return AssetCategory.FUND;
    }

    private String extractSector(String query) {
        if (query.contains("医药") || query.contains("医疗")) return "医药";
        if (query.contains("科技") || query.contains("半导体") || query.contains("芯片")) return "科技";
        if (query.contains("消费") || query.contains("白酒")) return "消费";
        if (query.contains("新能源") || query.contains("光伏")) return "新能源";
        return "优质";
    }

    private ExecutionPlan fallbackSingleTask(AssetCategory category, String taskType, String desc) {
        return ExecutionPlan.builder()
                .assetCategory(category)
                .isComplex(false)
                .summary(desc)
                .steps(List.of(
                        SubTask.builder()
                                .stepIndex(1)
                                .taskType(taskType)
                                .description(desc)
                                .dependsOn(List.of())
                                .outputKey("result")
                                .params(Map.of())
                                .build()
                ))
                .build();
    }
}
