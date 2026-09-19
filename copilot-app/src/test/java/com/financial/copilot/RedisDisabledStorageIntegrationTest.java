package com.financial.copilot;

import com.financial.copilot.agent.core.dag.runtime.checkpoint.DagCheckpoint;
import com.financial.copilot.agent.core.dag.runtime.checkpoint.DagCheckpointStore;
import com.financial.copilot.agent.core.dag.runtime.checkpoint.InMemoryDagCheckpointStore;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.ExecutionGraphSnapshot;
import com.financial.copilot.agent.core.memory.ShortTermMemoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "copilot.integration", matches = "true")
@SpringBootTest(properties = {
        "copilot.redis.enabled=false",
        "spring.data.redis.host=unreachable.invalid"
})
class RedisDisabledStorageIntegrationTest {

    @Autowired ShortTermMemoryService shortMemory;
    @Autowired DagCheckpointStore checkpointStore;
    @Autowired JdbcTemplate jdbc;

    @Test
    void startsWithoutRedisAndUsesLocalMemoryAndCheckpointStores() {
        String key = "redis-off-" + UUID.randomUUID();
        shortMemory.addMessage(key, "recent fact");
        DagCheckpoint checkpoint = new DagCheckpoint(
                UUID.randomUUID().toString(), 7L, UUID.randomUUID(), null,
                key, "prompt", false, null,
                ExecutionGraphSnapshot.from(new ExecutionGraph("redis-off")),
                Map.of(), Map.of(), java.time.Instant.now());
        checkpointStore.saveCheckpoint(checkpoint);

        assertThat(checkpointStore).isInstanceOf(InMemoryDagCheckpointStore.class);
        assertThat(shortMemory.getContext(key)).containsExactly("recent fact");
        assertThat(checkpointStore.load(checkpoint.userId(), checkpoint.runId())).isPresent();
    }
}
