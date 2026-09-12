package com.financial.copilot;

import com.financial.copilot.agent.core.memory.LongTermMemoryService;
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
    @Autowired LongTermMemoryService longTerm;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    @Test
    void applicationStartsAndMemoryRoundTripsThroughPostgresAndRedis() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/v1/research/health").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("UP");

        String session = "startup-test-" + UUID.randomUUID();
        try {
            shortTerm.addMessage(session, "old".repeat(12000));
            shortTerm.addMessage(session, "recent fact");
            shortTerm.pruneIfNeeded(session);
            assertThat(shortTerm.getContext(session)).containsExactly("recent fact");

            longTerm.record(session, "persisted fact");
            redis.delete("ltm:" + session); // Force the real database read, not a cache-only round trip.
            assertThat(longTerm.retrieve(session, 5)).containsExactly("persisted fact");
            longTerm.recordRefinedFacts(session, List.of("refined fact"));
            assertThat(longTerm.getRefinedFacts(session)).singleElement()
                    .satisfies(fact -> {
                        assertThat(fact.getContent()).isEqualTo("refined fact");
                        assertThat(fact.getId()).isNotNull();
                        assertThat(fact.getCreatedAt()).isNotNull();
                    });
        } finally {
            redis.delete(List.of("shortterm:session:" + session, "ltm:" + session));
            jdbc.update("DELETE FROM refined_fact WHERE session_id = ?", session);
            jdbc.update("DELETE FROM long_term_memory WHERE session_id = ?", session);
        }
    }
}
