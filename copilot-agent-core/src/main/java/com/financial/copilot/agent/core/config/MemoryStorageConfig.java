package com.financial.copilot.agent.core.config;

import com.financial.copilot.agent.core.dag.runtime.checkpoint.DagCheckpointStore;
import com.financial.copilot.agent.core.dag.runtime.checkpoint.InMemoryDagCheckpointStore;
import com.financial.copilot.agent.core.memory.store.local.InMemoryLongTermMemoryCache;
import com.financial.copilot.agent.core.memory.store.local.InMemoryShortTermMemoryStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "copilot.redis.enabled", havingValue = "false")
public class MemoryStorageConfig {

    @Bean
    public InMemoryShortTermMemoryStore inMemoryShortTermMemoryStore() {
        return new InMemoryShortTermMemoryStore();
    }

    @Bean
    public InMemoryLongTermMemoryCache inMemoryLongTermMemoryCache() {
        return new InMemoryLongTermMemoryCache();
    }

    @Bean
    public DagCheckpointStore dagCheckpointStore() {
        return new InMemoryDagCheckpointStore();
    }
}
