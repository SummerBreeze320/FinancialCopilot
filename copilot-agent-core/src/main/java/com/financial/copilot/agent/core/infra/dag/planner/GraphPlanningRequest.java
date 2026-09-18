package com.financial.copilot.agent.core.infra.dag.planner;

import com.financial.copilot.agent.core.infra.llm.dto.LlmResponse;
import com.financial.copilot.domain.platform.user.entity.UserInvestmentProfile;

import java.util.function.Consumer;

public record GraphPlanningRequest(
        String prompt, String sessionKey, UserInvestmentProfile profile,
        Consumer<LlmResponse> usageConsumer, boolean enableThinking,
        com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest runRequest
) {
    public GraphPlanningRequest(String prompt, String sessionKey, UserInvestmentProfile profile, Consumer<LlmResponse> usageConsumer, boolean enableThinking) {
        this(prompt, sessionKey, profile, usageConsumer, enableThinking, null);
    }
}
