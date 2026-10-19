package com.financial.copilot.agent.core.infra.llm.execution;

import com.financial.copilot.agent.core.infra.context.ExecutionIdentity;
import com.financial.copilot.agent.core.infra.llm.dto.LlmResponse;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * <h1>单次模型调用的统一执行上下文</h1>
 */
public record LlmCallContext(
        String callType,
        ExecutionIdentity identity,
        String modelName,
        Consumer<LlmResponse> usageConsumer,
        Duration timeout,
        String provider
) {
    public LlmCallContext {
        Objects.requireNonNull(callType, "callType must not be null");
        if (provider == null || provider.isBlank()) {
            provider = "DEFAULT";
        }
    }

    public LlmCallContext(String callType, ExecutionIdentity identity, String modelName, Consumer<LlmResponse> usageConsumer, Duration timeout) {
        this(callType, identity, modelName, usageConsumer, timeout, "DEFAULT");
    }

    public static LlmCallContext of(String callType) {
        return new LlmCallContext(callType, null, null, null, null, "DEFAULT");
    }

    public static LlmCallContext of(String callType, ExecutionIdentity identity, String modelName, Consumer<LlmResponse> usageConsumer) {
        return new LlmCallContext(callType, identity, modelName, usageConsumer, null, "DEFAULT");
    }
}
