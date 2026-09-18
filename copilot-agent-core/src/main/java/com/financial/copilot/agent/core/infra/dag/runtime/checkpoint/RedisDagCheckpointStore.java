package com.financial.copilot.agent.core.infra.dag.runtime.checkpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * <h1>基于 Redis 的分布式 DAG 状态持久化快照存储</h1>
 * <p>支持跨 Pod 漂移断点续跑，默认设置 24 小时 TTL 滑动过期。</p>
 */
public class RedisDagCheckpointStore implements DagCheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(RedisDagCheckpointStore.class);
    private static final String KEY_PREFIX = "copilot:dag:checkpoint:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final InMemoryDagCheckpointStore fallbackStore = new InMemoryDagCheckpointStore();

    public RedisDagCheckpointStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
    }

    public RedisDagCheckpointStore() {
        this(null, new ObjectMapper().findAndRegisterModules());
    }

    @Override
    public void saveCheckpoint(DagCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.runId() == null) return;
        if (redisTemplate == null) {
            fallbackStore.saveCheckpoint(checkpoint);
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(checkpoint);
            redisTemplate.opsForValue().set(key(checkpoint.userId(), checkpoint.runId()), json, DEFAULT_TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize DagCheckpoint for runId={}", checkpoint.runId(), e);
            fallbackStore.saveCheckpoint(checkpoint);
        } catch (Exception e) {
            log.warn("Redis write failure, saving to in-memory fallback: runId={}", checkpoint.runId(), e);
            fallbackStore.saveCheckpoint(checkpoint);
        }
    }

    @Override
    public Optional<DagCheckpoint> load(Long userId, String runId) {
        if (runId == null) return Optional.empty();
        if (redisTemplate == null) {
            return fallbackStore.load(userId, runId);
        }

        try {
            String json = redisTemplate.opsForValue().get(key(userId, runId));
            if (json != null && !json.isBlank()) {
                DagCheckpoint cp = objectMapper.readValue(json, DagCheckpoint.class);
                return Optional.ofNullable(cp);
            }
        } catch (Exception e) {
            log.warn("Failed to read DagCheckpoint from Redis for runId={}, falling back to in-memory", runId, e);
        }
        return fallbackStore.load(userId, runId);
    }

    @Override
    public void clear(Long userId, String runId) {
        if (runId == null) return;
        fallbackStore.clear(userId, runId);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(key(userId, runId));
            } catch (Exception e) {
                log.warn("Failed to delete DagCheckpoint in Redis for runId={}", runId, e);
            }
        }
    }

    private String key(Long userId, String runId) {
        return KEY_PREFIX + (userId == null ? "anonymous" : userId) + ":" + runId;
    }
}
