package com.financial.copilot.agent.core.memory.store;

import java.time.Duration;
import java.util.List;

public interface ShortTermMemoryStore {
    void append(String key, String message, Duration ttl);
    List<String> read(String key);
    void replace(String key, List<String> messages, Duration ttl);
    void trim(String key, long start, long end);
    void removeEarliest(String key);
}
