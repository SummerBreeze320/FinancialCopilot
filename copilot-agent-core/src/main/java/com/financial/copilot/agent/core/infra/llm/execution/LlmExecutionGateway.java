package com.financial.copilot.agent.core.infra.llm.execution;

import com.financial.copilot.agent.core.infra.dag.scheduler.RuntimeCapacityProperties;
import com.financial.copilot.gateway.model.LlmResponse;
import com.financial.copilot.agent.core.infra.observability.RuntimeMetrics;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * <h1>全局唯一模型执行网关 (LLM Execution Gateway)</h1>
 * 统一汇聚所有 AgentScope 角色与 Planner 的底层模型调用，
 * 提供全局并发受控、超时保护、全链路 Token 审计与计费回调。
 */
@Component
public class LlmExecutionGateway {

    private final LlmCapacityLimiter limiter;
    private final Duration defaultTimeout;
    private final RuntimeMetrics metrics;

    @Autowired
    public LlmExecutionGateway(LlmCapacityLimiter limiter,
                               @Autowired(required = false) RuntimeCapacityProperties capacityProperties,
                               @Autowired(required = false) RuntimeMetrics metrics) {
        this.limiter = Objects.requireNonNull(limiter, "limiter must not be null");
        this.defaultTimeout = capacityProperties != null && capacityProperties.getLlmCallTimeout() != null
                ? capacityProperties.getLlmCallTimeout() : Duration.ofSeconds(120);
        this.metrics = metrics;
        if (this.metrics != null) {
            this.metrics.bindActiveLlmSupplier(() -> this.limiter.maxConcurrency() - this.limiter.availablePermits());
        }
    }

    public LlmExecutionGateway(LlmCapacityLimiter limiter, Duration defaultTimeout) {
        this(limiter, defaultTimeout, null);
    }

    public LlmExecutionGateway(LlmCapacityLimiter limiter, Duration defaultTimeout, RuntimeMetrics metrics) {
        this.limiter = Objects.requireNonNull(limiter, "limiter must not be null");
        this.defaultTimeout = defaultTimeout != null ? defaultTimeout : Duration.ofSeconds(120);
        this.metrics = metrics;
        if (this.metrics != null) {
            this.metrics.bindActiveLlmSupplier(() -> this.limiter.maxConcurrency() - this.limiter.availablePermits());
        }
    }

    public LlmExecutionGateway(LlmCapacityLimiter limiter) {
        this(limiter, Duration.ofSeconds(120), null);
    }

    public Flux<ChatResponse> execute(LlmCallContext context, Supplier<Flux<ChatResponse>> invocation) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(invocation, "invocation must not be null");

        Duration effectiveTimeout = context.timeout() != null ? context.timeout() : defaultTimeout;

        return Flux.usingWhen(
                limiter.acquireReactive(),
                permit -> {
                    long startNanos = System.nanoTime();
                    Flux<ChatResponse> stream = invocation.get();
                    if (stream == null) {
                        return Flux.empty();
                    }
                    if (effectiveTimeout != null && !effectiveTimeout.isZero() && !effectiveTimeout.isNegative()) {
                        stream = stream.timeout(effectiveTimeout);
                    }
                    return stream
                            .doOnNext(response -> recordUsage(context, response, startNanos))
                            .doOnError(error -> {
                                if (metrics != null) {
                                    metrics.recordLlmError(context.provider(), error.getClass().getSimpleName());
                                }
                            });
                },
                permit -> Mono.fromRunnable(permit::close),
                (permit, error) -> Mono.fromRunnable(() -> {
                    permit.close();
                    if (metrics != null) {
                        metrics.recordLlmError(context.provider(), error.getClass().getSimpleName());
                    }
                }),
                permit -> Mono.fromRunnable(permit::close)
        );
    }

    public LlmCapacityLimiter limiter() {
        return limiter;
    }

    public Duration defaultTimeout() {
        return defaultTimeout;
    }

    private void recordUsage(LlmCallContext context, ChatResponse response, long startNanos) {
        if (response == null || context.usageConsumer() == null) {
            return;
        }
        ChatUsage usage = response.getUsage();
        if (usage == null || usage.getTotalTokens() <= 0) {
            return;
        }
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        context.usageConsumer().accept(LlmResponse.builder()
                .model(context.modelName() != null ? context.modelName() : "unknown")
                .promptTokens(usage.getInputTokens())
                .completionTokens(usage.getOutputTokens())
                .totalTokens(usage.getTotalTokens())
                .latencyMs(latencyMs)
                .build());
    }
}
