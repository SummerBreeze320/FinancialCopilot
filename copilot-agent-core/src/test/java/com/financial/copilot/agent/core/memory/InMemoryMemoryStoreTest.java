package com.financial.copilot.agent.core.memory;

import com.financial.copilot.agent.core.memory.store.local.InMemoryShortTermMemoryStore;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMemoryStoreTest {
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    @Test
    void shortMemoryPreservesOrderTrimsAndExpiresWithoutRefreshingOnRead() {
        MutableClock clock = new MutableClock();
        var store = new InMemoryShortTermMemoryStore(clock);
        store.replace("a", List.of("old", "middle", "new"), Duration.ofMinutes(30));
        store.trim("a", 1, -1);
        store.removeEarliest("a");
        store.append("a", "last", Duration.ofMinutes(30));
        assertThat(store.read("a")).containsExactly("new", "last");
        clock.now = clock.now.plusSeconds(1800);
        assertThat(store.read("a")).isEmpty();
    }

    @Test
    void shortMemoryEvictsLeastRecentlyReadKeyAndIsolatesKeys() {
        var store = new InMemoryShortTermMemoryStore();
        for (int i = 0; i < 1000; i++) store.append("k" + i, "m", Duration.ofMinutes(30));
        store.read("k0");
        store.append("overflow", "x", Duration.ofMinutes(30));
        assertThat(store.read("k1")).isEmpty();
        assertThat(store.read("k0")).containsExactly("m");
        assertThat(store.read("overflow")).containsExactly("x");
    }
}
