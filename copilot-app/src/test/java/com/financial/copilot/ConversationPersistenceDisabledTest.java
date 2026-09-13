package com.financial.copilot;

import com.financial.copilot.agent.core.conversation.ConversationPersistenceDisabledException;
import com.financial.copilot.agent.core.conversation.ConversationService;
import com.financial.copilot.agent.core.dag.runtime.checkpoint.DagCheckpoint;
import com.financial.copilot.agent.core.dag.runtime.checkpoint.DagCheckpointStore;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.ExecutionGraphSnapshot;
import com.financial.copilot.agent.core.memory.LongTermMemoryService;
import com.financial.copilot.agent.core.memory.ShortTermMemoryService;
import com.financial.copilot.config.security.SecurityUtils;
import com.financial.copilot.domain.conversation.entity.ConversationRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "copilot.integration", matches = "true")
@SpringBootTest(properties = "copilot.conversation.persistence-enabled=false")
class ConversationPersistenceDisabledTest {

    @Autowired ConversationService service;
    @Autowired ShortTermMemoryService shortMemory;
    @Autowired LongTermMemoryService longMemory;
    @Autowired DagCheckpointStore checkpointStore;
    @Autowired JdbcTemplate jdbc;

    private static final long owner = 7L;

    @Test
    void disabledModeWritesMemoryAndCheckpointButNoConversationRows() {
        ConversationRun run = service.beginRun(owner, null, UUID.randomUUID(), "prompt");
        String key = SecurityUtils.sessionKey(owner, run.conversationId().toString());
        service.complete(owner, run, key, "prompt", "report", Map.of());
        checkpointStore.saveCheckpoint(new DagCheckpoint(
                run.runId().toString(), owner,
                run.conversationId(), null, key, "prompt", false, null,
                ExecutionGraphSnapshot.from(new ExecutionGraph("disabled")), Map.of(), Map.of(), java.time.Instant.now()));

        assertThat(run.assistantMessageId()).isNull();
        assertThat(checkpointStore).isInstanceOf(
                com.financial.copilot.agent.core.dag.runtime.checkpoint.InMemoryDagCheckpointStore.class);
        assertThat(shortMemory.getContext(key)).containsExactly("USER: prompt", "ASSISTANT: report");
        assertThat(longMemory.retrieve(key, 10)).contains("report");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation_message WHERE run_id = ?",
                Long.class, run.runId())).isZero();

        assertThatThrownBy(() -> service.listConversations(owner, null, 20))
                .isInstanceOf(ConversationPersistenceDisabledException.class);

        try {
            jdbc.update("DELETE FROM long_term_memory WHERE session_id = ?", key);
        } catch (Exception ignored) {
        }
    }
}
