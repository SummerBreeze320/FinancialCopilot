package com.financial.copilot.agent.core.memory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.financial.copilot.agent.core.config.AsyncConfig;
import com.financial.copilot.agent.core.config.RedisConfig;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import com.financial.copilot.agent.core.event.WorkflowFinishedEvent;
import com.financial.copilot.agent.core.llm.dto.LlmRequest;
import com.financial.copilot.agent.core.llm.service.LlmService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
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
    void memoryServicesCanBeCreatedWithoutCircularReferences() {
        new ApplicationContextRunner()
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withUserConfiguration(ShortTermMemoryService.class, ContextReducer.class)
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(ShortTermMemoryService.class));
    }

    @Test
    void workflowEventRefinesMemoryOffThePublisherThread() {
        var shortTerm = mock(ShortTermMemoryService.class);
        when(shortTerm.getContext("test-session")).thenReturn(List.of("A fact"));
        var llm = mock(LlmService.class);
        var worker = new CompletableFuture<Thread>();
        when(llm.chat(any(LlmRequest.class))).thenAnswer(invocation -> {
            worker.complete(Thread.currentThread());
            return "A fact";
        });
        new ApplicationContextRunner()
                .withBean(ShortTermMemoryService.class, () -> shortTerm)
                .withBean(LongTermMemoryService.class, () -> mock(LongTermMemoryService.class))
                .withBean(LlmService.class, () -> llm)
                .withUserConfiguration(AsyncConfig.class, MemoryRefinementTask.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Thread publisher = Thread.currentThread();
                    context.publishEvent(new WorkflowFinishedEvent(this, "test-session"));
                    assertThat(worker.get(5, TimeUnit.SECONDS)).isNotSameAs(publisher);
                });
    }
}
