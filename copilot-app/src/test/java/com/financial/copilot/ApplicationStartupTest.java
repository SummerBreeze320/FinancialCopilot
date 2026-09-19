package com.financial.copilot;

import com.financial.copilot.agent.core.memory.ShortTermMemoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in: uses the configured PostgreSQL and Redis, never calls a remote LLM. */
@EnabledIfSystemProperty(named = "copilot.integration", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationStartupTest {
    @LocalServerPort int port;
    @Autowired ShortTermMemoryService shortTerm;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    @Test
    void applicationStartsAndMemoryRoundTripsThroughDatabaseAndRedis() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/v1/research/health").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("UP");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name IN ('research_conversation','conversation_message','agent_tool_audit') AND (table_schema = DATABASE() OR table_schema = 'public')",
                Integer.class)).isEqualTo(3);

        String session = "startup-test-" + UUID.randomUUID();
        try {
            shortTerm.addMessage(session, "old".repeat(12000));
            shortTerm.addMessage(session, "recent fact");
            shortTerm.pruneIfNeeded(session);
            assertThat(shortTerm.getContext(session)).containsExactly("recent fact");
        } finally {
            redis.delete(List.of("shortterm:session:" + session));
        }
    }
}
