package com.financial.copilot.agent.core.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.service.DeepSeekClientService;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 意图识别与任务规划主管 Agent (Planner)
 */
@Slf4j
@Component
public class PlannerAgent {

    private final DeepSeekClientService clientService;
    private final ObjectMapper objectMapper;

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

    public PlannerAgent(DeepSeekClientService clientService, ObjectMapper objectMapper) {
        this.clientService = clientService;
        this.objectMapper = objectMapper;
    }

    @Data
    @Builder
    public static class PlanResult {
        private String intent;
        private String primaryCode;
        private String secondaryCode;
        private String managerName;
        private String sectorTheme;
        private String rawUserPrompt;
    }

    public PlanResult plan(String userPrompt) {
        log.info("[PLANNER] 正在解析用户意图: prompt={}", userPrompt);
        String response = clientService.chat(SYSTEM_PROMPT, userPrompt);
        try {
            // 清理可能包含的 markdown 代码块包裹
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
