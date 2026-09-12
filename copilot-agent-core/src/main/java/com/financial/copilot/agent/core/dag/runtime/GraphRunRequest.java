package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.llm.dto.LlmResponse;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;

import java.util.Objects;
import java.util.function.Consumer;

public record GraphRunRequest(
        String runId,
        Long userId,
        String sessionId,
        String prompt,
        boolean enableThinking,
        UserInvestmentProfile profile,
        Consumer<LlmResponse> usageConsumer,
        RunMode mode
) {
    public GraphRunRequest {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(prompt, "prompt");
        mode = mode == null ? RunMode.SYNC : mode;
    }
}
