package com.financial.copilot.agent.core.agents.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.service.DeepSeekClientService;
import com.financial.copilot.agent.tools.fund.FundScreeningTool;
import com.financial.copilot.common.fund.dto.FundScreeningCriteria;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>公募基金智能筛选专员 Agent (Fund Screener)</h1>
 * <p>
 * 职责：负责将用户自然语言诉求通过大模型精准提取为强类型选基 DSL 条件 {@link FundScreeningCriteria}，
 * 并调用底层基金只读筛选工具获取候选标的池。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundScreenerAgent {

    private final DeepSeekClientService clientService;
    private final FundScreeningTool screeningTool;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
        你是一个资深公募基金量化筛选专员 ScreenerAgent。
        请从用户自然语言需求中提取结构化筛选条件 JSON:
        {
          "fundType": "股票型|偏股混合型|债券型|指数型 (未说明则为 null)",
          "sectorTheme": "医药|科技|消费等关键词 (未说明则为 null)",
          "minScaleInBillion": 最低规模数字 (如 10.0，未说明为 null),
          "maxScaleInBillion": 最高规模数字 (未说明为 null),
          "maxDrawdown3YLimit": 最大回撤上限 (未说明为 null),
          "minSharpe3Y": 最低夏普 (未说明为 null),
          "minReturn3Y": 最低年化收益率 (未说明为 null),
          "minManagerTenureYears": 最低经理年限 (未说明为 null),
          "sortBy": "SCALE|RETURN_3Y|SHARPE_3Y",
          "sortOrder": "DESC",
          "limit": 10
        }
        严格输出合法的 JSON 格式，禁止附带任何多余文字。
        """;

    public FundScreenerAgent(DeepSeekClientService clientService, FundScreeningTool screeningTool, ObjectMapper objectMapper) {
        this.clientService = clientService;
        this.screeningTool = screeningTool;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行自然语言基金初筛
     *
     * @param userPrompt 用户输入的问题或选基要求
     * @return 命中基金的 JSON 格式列表字符串
     */
    public String executeScreening(String userPrompt) {
        log.info("[FUND-SCREENER] 正在进行基金自然语言筛选: prompt={}", userPrompt);
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
            log.warn("[FUND-SCREENER] 解析筛选 Criteria 失败，启用稳健基础条件: error={}", e.getMessage());
            criteria = new FundScreeningCriteria("偏股混合型", null, 5.0, null, null, null, null, null, "SCALE", "DESC", 10);
        }

        return screeningTool.screenFunds(criteria);
    }
}
