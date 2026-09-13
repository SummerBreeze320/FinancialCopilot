package com.financial.copilot;

import com.financial.copilot.agent.core.conversation.ConversationService;
import com.financial.copilot.agent.core.memory.LongTermMemoryService;
import com.financial.copilot.agent.core.memory.ShortTermMemoryService;
import com.financial.copilot.config.security.SecurityUtils;
import com.financial.copilot.domain.conversation.entity.ConversationMessage;
import com.financial.copilot.domain.conversation.entity.ConversationRun;
import com.financial.copilot.domain.conversation.exception.ConversationNotFoundException;
import com.financial.copilot.domain.conversation.port.ConversationPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "copilot.integration", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationPersistenceTest {

    @Autowired ConversationService service;
    @Autowired ConversationPort port;
    @Autowired ShortTermMemoryService shortMemory;
    @Autowired LongTermMemoryService longMemory;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    private static final long owner = 7_000_000_001L;
    private static final long stranger = 7_000_000_002L;

    @Test
    void historySurvivesRedisLossAndForeignUserCannotReadIt() {
        ConversationRun run = service.beginRun(owner, null, UUID.randomUUID(), "prompt");
        String key = SecurityUtils.sessionKey(owner, run.conversationId().toString());
        service.complete(owner, run, key, "prompt", "report", java.util.Map.of());

        redis.delete("shortterm:session:" + key);

        assertThat(service.recentContext(owner, run.conversationId(), key, 20))
                .containsExactly("USER: prompt", "ASSISTANT: report");

        assertThatThrownBy(() -> port.listMessages(stranger, run.conversationId(), null, 20))
                .isInstanceOf(ConversationNotFoundException.class);

        cleanupConversation(run.conversationId(), run.runId());
    }

    @Test
    void concurrentStartsAllocateSixUniqueSequences() throws Exception {
        ConversationRun first = service.beginRun(owner, null, UUID.randomUUID(), "prompt-1");
        String key1 = SecurityUtils.sessionKey(owner, first.conversationId().toString());
        service.complete(owner, first, key1, "prompt-1", "report-1", java.util.Map.of());

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<ConversationRun> run2Ref = new AtomicReference<>();
        AtomicReference<ConversationRun> run3Ref = new AtomicReference<>();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            executor.submit(() -> {
                start.await();
                ConversationRun r = service.beginRun(owner, first.conversationId(), UUID.randomUUID(), "prompt-2");
                run2Ref.set(r);
                String key = SecurityUtils.sessionKey(owner, first.conversationId().toString());
                service.complete(owner, r, key, "prompt-2", "report-2", java.util.Map.of());
                return null;
            });
            executor.submit(() -> {
                start.await();
                ConversationRun r = service.beginRun(owner, first.conversationId(), UUID.randomUUID(), "prompt-3");
                run3Ref.set(r);
                String key = SecurityUtils.sessionKey(owner, first.conversationId().toString());
                service.complete(owner, r, key, "prompt-3", "report-3", java.util.Map.of());
                return null;
            });
            start.countDown();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } finally {
            if (!executor.isTerminated()) executor.shutdownNow();
        }

        var messages = port.listMessages(owner, first.conversationId(), null, 20).items();
        assertThat(messages).extracting(ConversationMessage::sequenceNo)
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L)
                .doesNotHaveDuplicates();

        cleanupConversation(first.conversationId(), first.runId());
    }

    private void cleanupConversation(UUID conversationId, UUID runId) {
        try {
            String key = SecurityUtils.sessionKey(owner, conversationId.toString());
            redis.delete("shortterm:session:" + key);
            redis.delete("ltm:" + key);
            jdbc.update("DELETE FROM agent_tool_audit WHERE conversation_id = ?", conversationId);
            jdbc.update("DELETE FROM conversation_message WHERE conversation_id = ?", conversationId);
            jdbc.update("DELETE FROM research_conversation WHERE id = ?", conversationId);
        } catch (Exception ignored) {
        }
    }
}
