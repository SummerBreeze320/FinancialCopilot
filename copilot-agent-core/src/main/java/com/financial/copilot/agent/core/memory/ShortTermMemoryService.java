package com.financial.copilot.agent.core.memory;

import com.financial.copilot.agent.core.memory.store.ShortTermMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Service managing short-term memory for a single execution session.
 * Delegates storage to ShortTermMemoryStore (Redis or in-memory) with a 30-minute TTL.
 */
@Service
public class ShortTermMemoryService {
    private static final Logger log = LoggerFactory.getLogger(ShortTermMemoryService.class);

    private final ShortTermMemoryStore store;
    private final long ttlMinutes;
    private final int tokenThreshold;
    private final ContextReducer contextReducer;

    public ShortTermMemoryService(ShortTermMemoryStore store,
                                  ContextReducer contextReducer) {
        this.store = store;
        this.contextReducer = contextReducer;
        this.ttlMinutes = 30L;
        this.tokenThreshold = 8000;
    }

    public void addMessage(String sessionId, String message) {
        String key = memoryKey(sessionId);
        store.append(key, message, Duration.ofMinutes(ttlMinutes));
        log.debug("[ShortTermMemory] Added message to {}", key);
    }

    public List<String> getContext(String sessionId) {
        return store.read(memoryKey(sessionId));
    }

    public List<String> getAllMessages(String sessionId) {
        return getContext(sessionId);
    }

    public void pruneIfNeeded(String sessionId) {
        List<String> context = getContext(sessionId);
        if (context == null || context.isEmpty()) {
            return;
        }
        int currentTokens = TokenUtil.estimateTokens(context);
        if (currentTokens > tokenThreshold) {
            log.info("[ShortTermMemory] Token budget exceeded ({} > {}), triggering reduction", currentTokens, tokenThreshold);
            int removed = contextReducer.messagesToRemove(context, tokenThreshold);
            store.trim(memoryKey(sessionId), removed, -1);
        }
    }

    public void removeEarliest(String sessionId) {
        store.removeEarliest(memoryKey(sessionId));
    }

    public void replaceContext(String sessionId, List<String> values) {
        String key = memoryKey(sessionId);
        store.replace(key, values, Duration.ofMinutes(ttlMinutes));
        if (values != null && !values.isEmpty()) {
            pruneIfNeeded(sessionId);
        }
    }

    private String memoryKey(String sessionId) {
        return "shortterm:session:" + sessionId;
    }

    private String executionLogKey(String sessionId) {
        return "executionlog:session:" + sessionId;
    }
}
