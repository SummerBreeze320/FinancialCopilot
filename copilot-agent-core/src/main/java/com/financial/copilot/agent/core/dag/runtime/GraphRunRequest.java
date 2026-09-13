package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.llm.dto.LlmResponse;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;

import java.util.Objects;
import java.util.function.Consumer;

public record GraphRunRequest(
        String runId,
        Long userId,
        java.util.UUID conversationId,
        Long assistantMessageId,
        String sessionKey,
        String prompt,
        boolean enableThinking,
        UserInvestmentProfile profile,
        Consumer<LlmResponse> usageConsumer,
        RunMode mode
) {
    public GraphRunRequest {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(sessionKey, "sessionKey");
        Objects.requireNonNull(prompt, "prompt");
        mode = mode == null ? RunMode.SYNC : mode;
    }
}
