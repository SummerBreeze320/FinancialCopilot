package com.financial.copilot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.fund.port.FundDataPort;
import org.springframework.stereotype.Component;

/**
 * <h1>基金量化指标分析工具 (顶层兼容门面)</h1>
 * <p>
 * 推荐迁移使用专属领域实现: {@link com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool}
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class FundQuantAnalysisTool extends com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool {

    public FundQuantAnalysisTool(FundDataPort fundDataPort, ObjectMapper objectMapper) {
        super(fundDataPort, objectMapper);
    }
}
