package com.financial.copilot.agent.tools.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * <h1>基金智能筛选只读工具 (Fund Screening Tool)</h1>
 * <p>
 * 供 ScreenerAgent 使用。待接入外部 HTTP 选基服务。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundScreeningTool {

    private final ObjectMapper objectMapper;

    public FundScreeningTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String screenFunds(Map<String, Object> criteria) {
        log.info("[TOOL CALL-FUND] 执行多维选基: criteria={}", criteria);
        return "[{\"fundCode\":\"005827.OF\",\"fundName\":\"易方达蓝筹精选\"},{\"fundCode\":\"161005.OF\",\"fundName\":\"富国天惠成长混合\"}]";
    }
}
