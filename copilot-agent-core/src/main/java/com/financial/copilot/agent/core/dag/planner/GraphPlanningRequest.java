package com.financial.copilot.agent.core.dag.planner;

import com.financial.copilot.agent.core.llm.dto.LlmResponse;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;

import java.util.function.Consumer;

public record GraphPlanningRequest(
        String prompt, String sessionId, UserInvestmentProfile profile,
        Consumer<LlmResponse> usageConsumer, boolean enableThinking
) {}
