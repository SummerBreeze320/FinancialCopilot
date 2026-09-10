package com.financial.copilot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.fund.port.FundDataPort;
import org.springframework.stereotype.Component;

/**
 * <h1>基金智能多维筛选工具 (顶层兼容门面)</h1>
 * <p>
 * 推荐迁移使用专属领域实现: {@link com.financial.copilot.agent.tools.fund.FundScreeningTool}
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class FundScreeningTool extends com.financial.copilot.agent.tools.fund.FundScreeningTool {

    public FundScreeningTool(FundDataPort fundDataPort, ObjectMapper objectMapper) {
        super(fundDataPort, objectMapper);
    }
}
