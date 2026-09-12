package com.financial.copilot.agent.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import com.financial.copilot.agent.core.memory.TokenUtil;

import java.time.Duration;
import java.util.List;

/**
 * Service managing short‑term memory for a single execution session.
 * It stores recent messages in Redis with a TTL (default 30 minutes) and
 * provides utilities for pruning based on token budget.
 */
@Service
public class ShortTermMemoryService {
    private static final Logger log = LoggerFactory.getLogger(ShortTermMemoryService.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final long ttlMinutes;
    private final int tokenThreshold;
    private final ContextReducer contextReducer;

    public ShortTermMemoryService(RedisTemplate<String, String> redisTemplate,
                                 ContextReducer contextReducer) {
        this.redisTemplate = redisTemplate;
        this.contextReducer = contextReducer;
        // These values could be externalized via application.yml
        this.ttlMinutes = 30L;
        this.tokenThreshold = 8000; // example token limit
    }

    /**
     * Append a message to the session memory.
     * The key is scoped by session id (provided by caller).
     */
    public void addMessage(String sessionId, String message) {
        String key = memoryKey(sessionId);
        redisTemplate.opsForList().rightPush(key, message);
        redisTemplate.expire(key, Duration.ofMinutes(ttlMinutes));
        log.debug("[ShortTermMemory] Added message to {} (size now {})", key,
                redisTemplate.opsForList().size(key));
    }

    /**
     * Retrieve the whole context for a session.
     */
    @SuppressWarnings("unchecked")
    public List<String> getContext(String sessionId) {
        String key = memoryKey(sessionId);
        return (List<String>) redisTemplate.opsForList().range(key, 0, -1);
    }

    /**
     * Retrieve all messages for a session (full short‑term memory).
     */
    public List<String> getAllMessages(String sessionId) {
        // Delegates to getContext which already returns the full list.
        return getContext(sessionId);
    }

    /**
     * Prune the memory if token budget is exceeded.
     */
    public void pruneIfNeeded(String sessionId) {
        List<String> context = getContext(sessionId);
        if (context == null || context.isEmpty()) {
            return;
        }
        int currentTokens = TokenUtil.estimateTokens(context);
        if (currentTokens > tokenThreshold) {
            log.info("[ShortTermMemory] Token budget exceeded ({} > {}), triggering reduction", currentTokens, tokenThreshold);
            int removed = contextReducer.messagesToRemove(context, tokenThreshold);
            redisTemplate.opsForList().trim(memoryKey(sessionId), removed, -1);
        }
    }

    /**
     * Remove the earliest (oldest) message from the session memory.
     */
    public void removeEarliest(String sessionId) {
        String key = memoryKey(sessionId);
        // Pop from left
        redisTemplate.opsForList().leftPop(key);
    }



    private String memoryKey(String sessionId) {
        return "shortterm:session:" + sessionId;
    }

    private String executionLogKey(String sessionId) {
        return "executionlog:session:" + sessionId;
    }
}
