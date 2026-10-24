package com.financial.copilot.agent.core.infra.llm.execution;

import com.financial.copilot.agent.core.infra.context.ExecutionIdentity;
import com.financial.copilot.gateway.model.LlmResponse;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class LlmExecutionGatewayTest {

    @Test
    void plannerAndBusinessCallsShareTheSameLimit() {
        LlmCapacityLimiter limiter = new LlmCapacityLimiter(1);
        LlmExecutionGateway gateway = new LlmExecutionGateway(limiter, Duration.ofSeconds(5));

        StepVerifier.create(Flux.merge(
                gateway.execute(context("planner"), slowCall()),
                gateway.execute(context("analysis"), slowCall())
        ))
                .expectNextCount(2)
                .verifyComplete();

        assertThat(limiter.maxObservedConcurrency()).isEqualTo(1);
        assertThat(limiter.availablePermits()).isEqualTo(1);
    }

    @Test
    void cancellationAndErrorReleasePermit() {
        LlmCapacityLimiter limiter = new LlmCapacityLimiter(1);
        LlmExecutionGateway gateway = new LlmExecutionGateway(limiter, Duration.ofSeconds(5));

        StepVerifier.create(gateway.execute(context("analysis"), () -> Flux.error(new IllegalStateException("boom"))))
                .expectError(IllegalStateException.class)
                .verify();

        assertThat(limiter.availablePermits()).isEqualTo(1);
    }

    @Test
    void cancellationReleasesPermit() {
        LlmCapacityLimiter limiter = new LlmCapacityLimiter(1);
        LlmExecutionGateway gateway = new LlmExecutionGateway(limiter, Duration.ofSeconds(5));

        StepVerifier.create(gateway.execute(context("analysis"), slowCall()), 0)
                .thenCancel()
                .verify();

        for (int i = 0; i < 50 && limiter.availablePermits() < 1; i++) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
        assertThat(limiter.availablePermits()).isEqualTo(1);
    }

    @Test
    void recordsUsageWhenConsumerPresent() {
        LlmCapacityLimiter limiter = new LlmCapacityLimiter(2);
        LlmExecutionGateway gateway = new LlmExecutionGateway(limiter, Duration.ofSeconds(5));
        AtomicReference<LlmResponse> recorded = new AtomicReference<>();

        LlmCallContext ctx = new LlmCallContext(
                "analysis",
                new ExecutionIdentity("DEFAULT", 1L, UUID.randomUUID(), "wf-1", "sess-1"),
                "gpt-4o",
                recorded::set,
                null
        );

        ChatResponse response = ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("result").build()))
                .usage(new ChatUsage(10, 20, 0))
                .finishReason("stop")
                .build();

        StepVerifier.create(gateway.execute(ctx, () -> Flux.just(response)))
                .expectNext(response)
                .verifyComplete();

        assertThat(recorded.get()).isNotNull();
        assertThat(recorded.get().getModel()).isEqualTo("gpt-4o");
        assertThat(recorded.get().getPromptTokens()).isEqualTo(10);
        assertThat(recorded.get().getCompletionTokens()).isEqualTo(20);
        assertThat(recorded.get().getTotalTokens()).isEqualTo(30);
    }

    private LlmCallContext context(String callType) {
        return LlmCallContext.of(callType);
    }

    private Supplier<Flux<ChatResponse>> slowCall() {
        return () -> Flux.just(ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("response").build()))
                        .finishReason("stop")
                        .build())
                .delayElements(Duration.ofMillis(80));
    }
}
