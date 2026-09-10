package com.financial.copilot.agent.tools;

import com.financial.copilot.data.fund.mapper.FundReportVectorMapper;
import org.springframework.stereotype.Component;

/**
 * 基金定期报告定性策略观点召回工具 (兼容入口)
 */
@Component
public class FundReportRetrieverTool extends com.financial.copilot.agent.tools.fund.FundReportRetrieverTool {

    public FundReportRetrieverTool(FundReportVectorMapper reportVectorMapper) {
        super(reportVectorMapper);
    }
}
