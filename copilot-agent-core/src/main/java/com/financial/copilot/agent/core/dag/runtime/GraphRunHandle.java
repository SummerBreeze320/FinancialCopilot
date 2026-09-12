package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.common.event.ResearchStreamEvent;
import reactor.core.publisher.Flux;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public record GraphRunHandle(
        String runId,
        Flux<ResearchStreamEvent> events,
        CompletableFuture<GraphRunResult> completion,
        Consumer<String> cancellation
) {
    public void cancel(String reason) {
        cancellation.accept(reason);
    }
}
