package com.financial.copilot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.fund.port.FundDataPort;
import org.springframework.stereotype.Component;

/**
 * <h1>基金持仓穿透查询工具 (顶层兼容门面)</h1>
 * <p>
 * 推荐迁移使用专属领域实现: {@link com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool}
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class FundHoldingsQueryTool extends com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool {

    public FundHoldingsQueryTool(FundDataPort fundDataPort, ObjectMapper objectMapper) {
        super(fundDataPort, objectMapper);
    }
}
