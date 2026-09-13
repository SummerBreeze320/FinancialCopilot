package com.financial.copilot.agent.core.memory.store.local;

import com.financial.copilot.agent.core.memory.store.ShortTermMemoryStore;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class InMemoryShortTermMemoryStore implements ShortTermMemoryStore {
    private final BoundedExpiringStore<String, List<String>> store;
    public InMemoryShortTermMemoryStore() { this(Clock.systemUTC()); }
    public InMemoryShortTermMemoryStore(Clock clock) { store = new BoundedExpiringStore<>(clock); }
    public void append(String key, String message, Duration ttl) {
        store.update(key, ttl, old -> {
            var messages = new ArrayList<String>(old == null ? List.of() : old);
            messages.add(message);
            return List.copyOf(messages);
        });
    }
    public List<String> read(String key) {
        List<String> result = store.get(key);
        return result == null ? List.of() : result;
    }
    public void replace(String key, List<String> messages, Duration ttl) {
        store.update(key, ttl, old -> messages.isEmpty() ? null : List.copyOf(messages));
    }
    public void trim(String key, long start, long end) {
        store.update(key, null, old -> {
            if (old == null) return null;
            long from = Math.max(0, start < 0 ? old.size() + start : start);
            long to = Math.min(old.size() - 1L, end < 0 ? old.size() + end : end);
            return from > to ? null : List.copyOf(old.subList((int) from, (int) to + 1));
        });
    }
    public void removeEarliest(String key) { trim(key, 1, -1); }
}
