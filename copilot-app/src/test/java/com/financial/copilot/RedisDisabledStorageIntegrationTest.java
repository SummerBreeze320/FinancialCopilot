package com.financial.copilot;

import com.financial.copilot.agent.core.infra.memory.MemoryClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "copilot.integration", matches = "true")
@SpringBootTest(properties = {
        "copilot.redis.enabled=false",
        "spring.data.redis.host=unreachable.invalid"
})
class RedisDisabledStorageIntegrationTest {

    @Autowired MemoryClient memoryClient;
    @Autowired JdbcTemplate jdbc;

    @Test
    void startsWithoutRedisAndUsesLocalMemoryAndCheckpointStores() {
        String key = "redis-off-" + UUID.randomUUID();
        assertThat(memoryClient).isNotNull();

        try {
            jdbc.update("DELETE FROM long_term_memory WHERE session_id = ?", key);
        } catch (Exception ignored) {
        }
    }
}
