package com.financial.copilot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.port.FundDataPort;
import org.springframework.stereotype.Component;

@Component
public class FundScreeningTool extends com.financial.copilot.agent.tools.fund.FundScreeningTool {
    public FundScreeningTool(FundDataPort fundDataPort, ObjectMapper objectMapper) {
        super(fundDataPort, objectMapper);
    }
}
