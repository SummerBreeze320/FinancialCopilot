package com.financial.copilot.agent.core.config;

import com.financial.copilot.agent.core.dag.runtime.checkpoint.DagCheckpointStore;
import com.financial.copilot.agent.core.dag.runtime.checkpoint.InMemoryDagCheckpointStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "copilot.redis.enabled", havingValue = "false")
public class MemoryStorageConfig {

    @Bean
    public DagCheckpointStore dagCheckpointStore() {
        return new InMemoryDagCheckpointStore();
    }
}
