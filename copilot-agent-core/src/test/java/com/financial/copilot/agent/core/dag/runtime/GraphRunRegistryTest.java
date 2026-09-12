package com.financial.copilot.agent.core.dag.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class GraphRunRegistryTest {

    @Test
    void activeRunsAreScopedByOwner() {
        GraphRunRegistry registry = new GraphRunRegistry();
        GraphRunHandle handle = new GraphRunHandle("run", reactor.core.publisher.Flux.never(),
                new CompletableFuture<>(), ignored -> {});

        registry.register(7L, handle);

        assertThat(registry.find(7L, "run")).contains(handle);
        assertThat(registry.find(8L, "run")).isEmpty();
        assertThat(registry.cancel(8L, "run", "foreign")).isFalse();
        assertThat(registry.cancel(7L, "run", "owner request")).isTrue();
    }
}
