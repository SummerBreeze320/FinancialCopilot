package com.financial.copilot.agent.core.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.service.DeepSeekClientService;
import com.financial.copilot.agent.tools.FundScreeningTool;
import com.financial.copilot.common.dto.FundScreeningCriteria;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 基金智能筛选专员 Agent (Screener)
 */
@Slf4j
@Component
public class ScreenerAgent {

    private final DeepSeekClientService clientService;
    private final FundScreeningTool screeningTool;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
        你是一个资深基金量化筛选专员 ScreenerAgent。
        请从用户自然语言需求中提取结构化筛选条件 JSON:
        {
          "fundType": "股票型|偏股混合型|债券型|指数型 (未说明则为 null)",
          "sectorTheme": "医药|科技|消费等关键词 (未说明则为 null)",
          "minScaleInBillion": 最低规模数字 (如 10.0，未说明为 null),
          "maxScaleInBillion": 最高规模数字 (未说明为 null),
          "maxDrawdown3YLimit": 最大回撤上限 (未说明为 null),
          "minSharpe3Y": 最低夏普 (未说明为 null),
          "sortBy": "SCALE|RETURN_3Y|SHARPE_3Y",
          "limit": 10
        }
        严格输出 JSON，禁止其它文字。
        """;

    public ScreenerAgent(DeepSeekClientService clientService, FundScreeningTool screeningTool, ObjectMapper objectMapper) {
        this.clientService = clientService;
        this.screeningTool = screeningTool;
        this.objectMapper = objectMapper;
    }

    public String executeScreening(String userPrompt) {
        log.info("[SCREENER] 正在进行自然语言筛选: prompt={}", userPrompt);
        String response = clientService.chat(SYSTEM_PROMPT, userPrompt);
        FundScreeningCriteria criteria;
        try {
            String cleanJson = response.trim();
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.substring(7);
            }
            if (cleanJson.endsWith("```")) {
                cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
            }
            criteria = objectMapper.readValue(cleanJson.trim(), FundScreeningCriteria.class);
        } catch (Exception e) {
            log.warn("解析筛选 Criteria 失败，使用基础条件: error={}", e.getMessage());
            criteria = new FundScreeningCriteria("偏股混合型", null, 5.0, null, null, null, null, null, "SCALE", "DESC", 10);
        }

        // 调用只读工具执行真实 SQL
        return screeningTool.executeScreening(criteria);
    }
}
