package com.financial.copilot.agent.core.agents.stock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.service.DeepSeekClientService;
import com.financial.copilot.agent.tools.stock.StockScreeningTool;
import com.financial.copilot.common.stock.dto.StockScreeningCriteria;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>股票智能多因子初筛专员 Agent (Stock Screener Agent)</h1>
 * <p>
 * 职责：负责将用户针对股票市场的自然语言诉求（例如 "找市盈率低于20、ROE大于15%的白酒或医药龙头股"）
 * 通过大模型精准提取为强类型的股票多因子筛选 DSL {@link StockScreeningCriteria}，
 * 并调用底层只读股票筛选工具 {@link StockScreeningTool} 获取候选股票池。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class StockScreenerAgent {

    private final DeepSeekClientService clientService;
    private final StockScreeningTool screeningTool;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
        你是一个精通 A 股与港股市场的股票多因子量化选股专员 StockScreenerAgent。
        请从用户自然语言需求中提取结构化股票筛选条件 JSON:
        {
          "exchange": "SSE|SZSE|BSE|HKEX (未指定为 null)",
          "sector": "行业板块关键词，如 食品饮料|医药生物|电子 (未指定为 null)",
          "minMarketCapBillion": 最低总市值数字，单位亿元 (如 500.0，未指定为 null),
          "maxPeTtm": 市盈率上限 (如 25.0，未指定为 null),
          "maxPb": 市净率上限 (未指定为 null),
          "minRoe": 最低净资产收益率百分比 (如 15.0，未指定为 null),
          "minDividendYield": 最低股息率百分比 (如 3.0，未指定为 null),
          "sortBy": "MARKET_CAP|ROE|PE|DIVIDEND_YIELD",
          "sortOrder": "DESC",
          "limit": 10
        }
        严格输出合法的 JSON 格式，严禁附带任何 Markdown 解释文字。
        """;

    /**
     * 构造函数，自动装配大模型服务与股票筛选工具
     *
     * @param clientService 大模型客户端服务
     * @param screeningTool 股票筛选只读工具
     * @param objectMapper  JSON 解析器
     */
    public StockScreenerAgent(DeepSeekClientService clientService,
                              StockScreeningTool screeningTool,
                              ObjectMapper objectMapper) {
        this.clientService = clientService;
        this.screeningTool = screeningTool;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行自然语言股票多因子筛选
     *
     * @param userPrompt 用户自然语言提问或选股条件
     * @return 命中的股票 JSON 数组字符串
     */
    public String executeScreening(String userPrompt) {
        log.info("[STOCK-SCREENER] 正在进行股票自然语言筛选: prompt={}", userPrompt);
        String response = clientService.chat(SYSTEM_PROMPT, userPrompt);
        StockScreeningCriteria criteria;
        try {
            String cleanJson = response.trim();
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.substring(7);
            }
            if (cleanJson.endsWith("```")) {
                cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
            }
            criteria = objectMapper.readValue(cleanJson.trim(), StockScreeningCriteria.class);
        } catch (Exception e) {
            log.warn("[STOCK-SCREENER] 解析股票筛选 Criteria 失败，启用稳健基础条件: error={}", e.getMessage());
            criteria = new StockScreeningCriteria(null, "消费", 100.0, 30.0, null, 12.0, null, "MARKET_CAP", "DESC", 10);
        }

        return screeningTool.screenStocks(criteria);
    }
}
