package com.financial.copilot.agent.core.infra.cache;

import com.financial.copilot.agent.tools.cache.DistributedToolCacheDelegate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * <h1>基于 Redis 的分布式二级工具缓存委托实现 (Redis Distributed Tool Cache Delegate)</h1>
 * <p>
 * 桥接 Redis，为各智能体节点提供统一的分布式工具执行缓存存储。
 * 遵循严格的防御性容错设计，Redis 连接异常时安全降级，不阻断主链路。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisDistributedToolCacheDelegate implements DistributedToolCacheDelegate {

    private final StringRedisTemplate redisTemplate;

    public RedisDistributedToolCacheDelegate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key));
        } catch (Exception e) {
            log.warn("[REDIS-TOOL-CACHE] 读取失败: key={}, error={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("[REDIS-TOOL-CACHE] 写入失败: key={}, error={}", key, e.getMessage());
        }
    }

    @Override
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("[REDIS-TOOL-CACHE] 删除失败: key={}, error={}", key, e.getMessage());
        }
    }

    @Override
    public void deletePattern(String keyPattern) {
        try {
            Set<String> keys = redisTemplate.keys(keyPattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("[REDIS-TOOL-CACHE] 模式删除失败: pattern={}, error={}", keyPattern, e.getMessage());
        }
    }
}
