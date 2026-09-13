package com.financial.copilot.agent.core.memory.store.redis;

import com.financial.copilot.agent.core.memory.store.LongTermMemoryCache;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "copilot.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisLongTermMemoryCache implements LongTermMemoryCache {

    private final StringRedisTemplate redis;

    public RedisLongTermMemoryCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public List<String> readRecent(String key, int limit) {
        Set<String> set = redis.opsForZSet().reverseRange(key, 0, limit - 1);
        return set != null ? new ArrayList<>(set) : List.of();
    }

    @Override
    public void put(String key, String content, Instant createdAt, Duration ttl) {
        double score = createdAt.toEpochMilli();
        redis.opsForZSet().add(key, content, score);
    }

    @Override
    public void replace(String key, List<CachedMemory> entries, Duration ttl) {
        redis.delete(key);
        if (entries != null) {
            for (CachedMemory entry : entries) {
                redis.opsForZSet().add(key, entry.content(), entry.createdAt().toEpochMilli());
            }
        }
    }

    @Override
    public void evict(String key) {
        redis.delete(key);
    }
}
