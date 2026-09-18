package com.financial.copilot.agent.core.infra.memory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.financial.copilot.agent.core.infra.config.AsyncConfig;
import com.financial.copilot.agent.core.infra.config.RedisConfig;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import com.financial.copilot.agent.core.infra.event.WorkflowFinishedEvent;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class MemoryContextTest {
    @Test
    void redisConnectionUsesConfiguredHostPortAndPassword() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withUserConfiguration(RedisConfig.class)
                .withPropertyValues("spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=16379", "spring.data.redis.password=test-only")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var config = context.getBean(LettuceConnectionFactory.class).getStandaloneConfiguration();
                    assertThat(config.getHostName()).isEqualTo("127.0.0.1");
                    assertThat(config.getPort()).isEqualTo(16379);
                    assertThat(config.getPassword().isPresent()).isTrue();
                });
    }

    @Test
    void workflowEventRefinesMemoryOffThePublisherThread() {
        var memoryClient = mock(MemoryClient.class);
        when(memoryClient.getContext("test-session")).thenReturn(List.of("USER: test", "ASSISTANT: response"));
        var worker = new CompletableFuture<Thread>();
        doAnswer(invocation -> {
            worker.complete(Thread.currentThread());
            return null;
        }).when(memoryClient).extractProfile(any(String.class), any(String.class));
        new ApplicationContextRunner()
                .withBean(MemoryClient.class, () -> memoryClient)
                .withUserConfiguration(AsyncConfig.class, MemoryRefinementTask.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Thread publisher = Thread.currentThread();
                    context.publishEvent(new WorkflowFinishedEvent(this, "test-session", 1L));
                    assertThat(worker.get(5, TimeUnit.SECONDS)).isNotSameAs(publisher);
                });
    }
}
