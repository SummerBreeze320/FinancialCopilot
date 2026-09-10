package com.financial.copilot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.port.FundDataPort;
import org.springframework.stereotype.Component;

@Component
public class FundQuantAnalysisTool extends com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool {
    public FundQuantAnalysisTool(FundDataPort fundDataPort, ObjectMapper objectMapper) {
        super(fundDataPort, objectMapper);
    }
}
