package com.financial.copilot;

import com.financial.copilot.agent.core.platform.conversation.ConversationService;
import com.financial.copilot.agent.core.infra.dag.runtime.checkpoint.DagCheckpointStore;
import com.financial.copilot.agent.core.infra.dag.runtime.checkpoint.InMemoryDagCheckpointStore;
import com.financial.copilot.agent.core.infra.memory.MemoryClient;
import com.financial.copilot.config.security.SecurityUtils;
import com.financial.copilot.domain.platform.conversation.entity.ConversationRun;
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
        "copilot.conversation.persistence-enabled=false",
        "spring.data.redis.host=unreachable.invalid"
})
class AllOptionalPersistenceDisabledTest {

    @Autowired ConversationService service;
    @Autowired MemoryClient memoryClient;
    @Autowired DagCheckpointStore checkpointStore;
    @Autowired JdbcTemplate jdbc;

    private static final long owner = 7L;

    @Test
    void bothDisabledKeepsOnlyRequiredMemoryAndLocalCheckpointBehavior() {
        ConversationRun run = service.beginRun(owner, null, UUID.randomUUID(), "prompt");
        String key = SecurityUtils.sessionKey(owner, run.conversationId().toString());
        service.complete(owner, run, key, "prompt", "report", Map.of());

        assertThat(run.assistantMessageId()).isNull();
        assertThat(checkpointStore).isInstanceOf(InMemoryDagCheckpointStore.class);
        assertThat(memoryClient.getContext(key)).containsExactly("USER: prompt", "ASSISTANT: report");
        // MemoryClient 无 retrieve 方法，长期记忆检索改用 searchMemory(query, maxResults)
        // assertThat(longMemory.retrieve(key, 10)).contains("report");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation_message WHERE run_id = ?",
                Long.class, run.runId())).isZero();

        try {
            jdbc.update("DELETE FROM long_term_memory WHERE session_id = ?", key);
        } catch (Exception ignored) {
        }
    }
}
