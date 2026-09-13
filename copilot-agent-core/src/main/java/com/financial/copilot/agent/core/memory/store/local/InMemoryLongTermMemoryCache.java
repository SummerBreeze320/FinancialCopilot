package com.financial.copilot.agent.core.memory.store.local;

import com.financial.copilot.agent.core.memory.store.LongTermMemoryCache;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InMemoryLongTermMemoryCache implements LongTermMemoryCache {
    private final BoundedExpiringStore<String, Map<String, Instant>> store;
    public InMemoryLongTermMemoryCache() { this(Clock.systemUTC()); }
    public InMemoryLongTermMemoryCache(Clock clock) { store = new BoundedExpiringStore<>(clock); }
    public List<String> readRecent(String key, int limit) {
        Map<String, Instant> entries = store.get(key);
        if (entries == null || limit <= 0) return List.of();
        return entries.entrySet().stream()
            .sorted(Map.Entry.<String, Instant>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
            .limit(limit).map(Map.Entry::getKey).toList();
    }
    public void put(String key, String content, Instant createdAt, Duration ttl) {
        store.update(key, ttl, old -> {
            Map<String, Instant> entries = new HashMap<>(old == null ? Map.of() : old);
            entries.put(content, createdAt);
            return Map.copyOf(entries);
        });
    }
    public void replace(String key, List<CachedMemory> memories, Duration ttl) {
        Map<String, Instant> entries = new HashMap<>();
        memories.forEach(memory -> entries.put(memory.content(), memory.createdAt()));
        store.update(key, ttl, old -> entries.isEmpty() ? null : Map.copyOf(entries));
    }
    public void evict(String key) { store.remove(key); }
}
