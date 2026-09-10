package com.financial.copilot.agent.core.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.service.DeepSeekClientService;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>意图识别与单阶段任务规划主管 Agent (Planner)</h1>
 * <p>
 * 职责：负责对单意图场景的用户提问进行分类与核心实体（基金代码、基金经理、板块主题）抽取。
 * 针对复杂多步骤投研工作流，推荐协同使用任务分解器 {@link com.financial.copilot.agent.core.pipeline.TaskDecomposer}。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class PlannerAgent {

    /**
     * 大模型客户端服务
     */
    private final DeepSeekClientService clientService;

    /**
     * JSON 对象序列化与反序列化器
     */
    private final ObjectMapper objectMapper;

    /**
     * 规划主管意图抽取 System Prompt
     */
    private static final String SYSTEM_PROMPT = """
        你是一个精通中国公募基金领域的投研主管 Planner Agent。
        请对用户的提问进行意图分类与实体抽取。
        分类枚举:
        - SCREENING: 用户想要按条件筛选、寻找一批基金 (如: '找近3年回撤小于15%的医药基金')
        - SINGLE_ANALYSIS: 用户询问单只基金或单个经理的深度分析 (如: '分析易方达蓝筹精选', '张坤怎么样')
        - COMPARISON: 用户想对比两只或多只基金/经理 (如: '对比易方达蓝筹与富国天惠', '张坤和朱少醒风格差异')
        - GENERAL_QA: 普通问答 (如: '什么是卡玛比率')
        
        必须输出严格的 JSON 格式:
        {
          "intent": "SCREENING|SINGLE_ANALYSIS|COMPARISON|GENERAL_QA",
          "primaryCode": "提取到的第一个基金代码或推荐代码，如 005827",
          "secondaryCode": "提取到的第二个基金代码，如 161005 (没有则为 null)",
          "managerName": "提取到的经理姓名 (没有则为 null)",
          "sectorTheme": "提取到的行业或概念主题 (没有则为 null)"
        }
        """;

    /**
     * 构造函数，注入依赖
     *
     * @param clientService 大模型调用服务
     * @param objectMapper  JSON 解析器
     */
    public PlannerAgent(DeepSeekClientService clientService, ObjectMapper objectMapper) {
        this.clientService = clientService;
        this.objectMapper = objectMapper;
    }

    /**
     * 单阶段规划解析结果载体
     */
    @Data
    @Builder
    public static class PlanResult {
        /**
         * 识别意图枚举 (SCREENING / SINGLE_ANALYSIS / COMPARISON / GENERAL_QA)
         */
        private String intent;

        /**
         * 主标的代码（如 "005827"）
         */
        private String primaryCode;

        /**
         * 对标辅助标的代码（如 "161005"）
         */
        private String secondaryCode;

        /**
         * 涉及的基金经理姓名（如 "张坤"）
         */
        private String managerName;

        /**
         * 涉及的行业或主题板块（如 "医药"、"消费"）
         */
        private String sectorTheme;

        /**
         * 用户原始自然语言 Prompt
         */
        private String rawUserPrompt;
    }

    /**
     * 解析用户输入并生成意图实体规划
     *
     * @param userPrompt 用户输入
     * @return 规划结果封装对象
     */
    public PlanResult plan(String userPrompt) {
        log.info("[PLANNER] 正在解析用户意图: prompt={}", userPrompt);
        String response = clientService.chat(SYSTEM_PROMPT, userPrompt);
        try {
            String cleanJson = response.trim();
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.substring(7);
            }
            if (cleanJson.endsWith("```")) {
                cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
            }
            JsonNode node = objectMapper.readTree(cleanJson.trim());

            return PlanResult.builder()
                    .intent(node.path("intent").asText("SINGLE_ANALYSIS"))
                    .primaryCode(node.path("primaryCode").asText("005827"))
                    .secondaryCode(node.path("secondaryCode").asText("161005"))
                    .managerName(node.path("managerName").asText(null))
                    .sectorTheme(node.path("sectorTheme").asText(null))
                    .rawUserPrompt(userPrompt)
                    .build();
        } catch (Exception e) {
            log.warn("解析 Planner 响应 JSON 失败，启用规则匹配兜底: response={}", response);
            String intent = "SINGLE_ANALYSIS";
            if (userPrompt.contains("对比") || userPrompt.contains("与") || userPrompt.contains("和")) {
                intent = "COMPARISON";
            } else if (userPrompt.contains("选") || userPrompt.contains("找") || userPrompt.contains("推荐")) {
                intent = "SCREENING";
            }
            return PlanResult.builder()
                    .intent(intent)
                    .primaryCode("005827")
                    .secondaryCode("161005")
                    .rawUserPrompt(userPrompt)
                    .build();
        }
    }
}
