package com.financial.copilot.agent.core.memory.store;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface LongTermMemoryCache {
    record CachedMemory(String content, Instant createdAt) {}
    List<String> readRecent(String key, int limit);
    void put(String key, String content, Instant createdAt, Duration ttl);
    void replace(String key, List<CachedMemory> entries, Duration ttl);
    void evict(String key);
}
