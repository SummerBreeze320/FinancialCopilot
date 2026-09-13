package com.financial.copilot.agent.core.memory.store.redis;

import com.financial.copilot.agent.core.memory.store.ShortTermMemoryStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "copilot.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisShortTermMemoryStore implements ShortTermMemoryStore {

    private final StringRedisTemplate redis;

    public RedisShortTermMemoryStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void append(String key, String message, Duration ttl) {
        redis.opsForList().rightPush(key, message);
        redis.expire(key, ttl);
    }

    @Override
    public List<String> read(String key) {
        List<String> result = redis.opsForList().range(key, 0, -1);
        return result != null ? result : List.of();
    }

    @Override
    public void replace(String key, List<String> messages, Duration ttl) {
        redis.delete(key);
        if (messages != null && !messages.isEmpty()) {
            redis.opsForList().rightPushAll(key, messages);
            redis.expire(key, ttl);
        }
    }

    @Override
    public void trim(String key, long start, long end) {
        redis.opsForList().trim(key, start, end);
    }

    @Override
    public void removeEarliest(String key) {
        redis.opsForList().leftPop(key);
    }
}
