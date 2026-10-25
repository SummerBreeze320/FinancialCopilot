package com.financial.copilot.agent.tools.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.index.qual.NonNegative;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <h1>高性能二级工具执行缓存实现 (Caffeine L1 + 可选分布式 L2)</h1>
 * <p>
 * 一级缓存采用高吞吐无锁 Caffeine，支持条目级动态 TTL 过期；
 * 二级缓存可选桥接 {@link DistributedToolCacheDelegate}（如 Redis）。当未配置或不可用时，透明降级为全内存运作。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class CaffeineToolExecutionCache implements ToolExecutionCache {

    private record CachedPayload(String resultJson, long ttlSeconds) {}

    private final ToolCacheProperties properties;
    private final DistributedToolCacheDelegate distributedDelegate;
    private final Cache<String, CachedPayload> l1Cache;

    private final AtomicLong l1HitCount = new AtomicLong();
    private final AtomicLong l2HitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong putCount = new AtomicLong();

    private volatile boolean distributedDegraded = false;
    private volatile long lastDistributedErrorTimestamp = 0L;
    private static final long RETRY_INTERVAL_MILLIS = 30_000L;

    @Autowired
    public CaffeineToolExecutionCache(
            ToolCacheProperties properties,
            @Autowired(required = false) @Nullable DistributedToolCacheDelegate distributedDelegate) {
        this.properties = properties != null ? properties : new ToolCacheProperties();
        this.distributedDelegate = distributedDelegate;

        this.l1Cache = Caffeine.newBuilder()
                .maximumSize(this.properties.getL1MaxSize())
                .expireAfter(new Expiry<String, CachedPayload>() {
                    @Override
                    public long expireAfterCreate(String key, CachedPayload value, long currentTime) {
                        return TimeUnit.SECONDS.toNanos(Math.max(1L, value.ttlSeconds()));
                    }

                    @Override
                    public long expireAfterUpdate(String key, CachedPayload value, long currentTime, @NonNegative long currentDuration) {
                        return TimeUnit.SECONDS.toNanos(Math.max(1L, value.ttlSeconds()));
                    }

                    @Override
                    public long expireAfterRead(String key, CachedPayload value, long currentTime, @NonNegative long currentDuration) {
                        return currentDuration;
                    }
                })
                .recordStats()
                .build();
    }

    @Override
    public Optional<String> get(String toolName, String cacheKey) {
        if (!properties.isEnabled() || cacheKey == null) {
            return Optional.empty();
        }

        // 1. 优先查 L1 内存缓存
        CachedPayload localPayload = l1Cache.getIfPresent(cacheKey);
        if (localPayload != null) {
            l1HitCount.incrementAndGet();
            log.debug("[TOOL-CACHE L1 HIT] tool={}, key={}", toolName, cacheKey);
            return Optional.ofNullable(localPayload.resultJson());
        }

        // 2. L1 未命中，尝试查 L2 分布式缓存
        if (isDistributedAvailable()) {
            try {
                Optional<String> remoteResult = distributedDelegate.get(cacheKey);
                if (remoteResult.isPresent()) {
                    l2HitCount.incrementAndGet();
                    log.debug("[TOOL-CACHE L2 HIT] tool={}, key={}", toolName, cacheKey);
                    // 回填 L1 缓存
                    l1Cache.put(cacheKey, new CachedPayload(remoteResult.get(), properties.getDefaultTtlSeconds()));
                    return remoteResult;
                }
            } catch (Exception e) {
                handleDistributedException("get", e);
            }
        }

        missCount.incrementAndGet();
        log.debug("[TOOL-CACHE MISS] tool={}, key={}", toolName, cacheKey);
        return Optional.empty();
    }

    @Override
    public void put(String toolName, String cacheKey, String resultJson, long ttlSeconds) {
        if (!properties.isEnabled() || cacheKey == null || resultJson == null) {
            return;
        }

        long actualTtl = ttlSeconds > 0 ? ttlSeconds : properties.getDefaultTtlSeconds();

        // 写入 L1 内存
        l1Cache.put(cacheKey, new CachedPayload(resultJson, actualTtl));
        putCount.incrementAndGet();

        // 写入 L2 分布式缓存
        if (isDistributedAvailable()) {
            try {
                distributedDelegate.put(cacheKey, resultJson, Duration.ofSeconds(actualTtl));
            } catch (Exception e) {
                handleDistributedException("put", e);
            }
        }
        log.debug("[TOOL-CACHE PUT] tool={}, key={}, ttl={}s", toolName, cacheKey, actualTtl);
    }

    @Override
    public void evict(String toolName, String cacheKey) {
        if (cacheKey == null) {
            return;
        }
        l1Cache.invalidate(cacheKey);
        if (isDistributedAvailable()) {
            try {
                distributedDelegate.delete(cacheKey);
            } catch (Exception e) {
                handleDistributedException("evict", e);
            }
        }
        log.debug("[TOOL-CACHE EVICT] tool={}, key={}", toolName, cacheKey);
    }

    @Override
    public void clear(String toolName) {
        String prefix = "tool:cache:" + toolName + ":";
        l1Cache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
        if (isDistributedAvailable()) {
            try {
                distributedDelegate.deletePattern(prefix + "*");
            } catch (Exception e) {
                handleDistributedException("clear", e);
            }
        }
        log.info("[TOOL-CACHE CLEAR] Cleared all cache for tool: {}", toolName);
    }

    @Override
    public void clearAll() {
        l1Cache.invalidateAll();
        if (isDistributedAvailable()) {
            try {
                distributedDelegate.deletePattern("tool:cache:*");
            } catch (Exception e) {
                handleDistributedException("clearAll", e);
            }
        }
        log.info("[TOOL-CACHE CLEAR ALL] Cleared all tool cache");
    }

    @Override
    public ToolCacheStats getStats() {
        return ToolCacheStats.builder()
                .hitCount(l1HitCount.get() + l2HitCount.get())
                .missCount(missCount.get())
                .putCount(putCount.get())
                .evictionCount(l1Cache.stats().evictionCount())
                .currentSize(l1Cache.estimatedSize())
                .build();
    }

    private boolean isDistributedAvailable() {
        if (!properties.isRedisEnabled() || distributedDelegate == null) {
            return false;
        }
        if (distributedDegraded) {
            if (System.currentTimeMillis() - lastDistributedErrorTimestamp > RETRY_INTERVAL_MILLIS) {
                distributedDegraded = false;
                log.info("[TOOL-CACHE] 分布式缓存重试探测恢复");
            } else {
                return false;
            }
        }
        return true;
    }

    private void handleDistributedException(String op, Exception e) {
        distributedDegraded = true;
        lastDistributedErrorTimestamp = System.currentTimeMillis();
        log.warn("[TOOL-CACHE DEGRADED] 分布式缓存操作失败 (op={}): {}, 自动降级为全内存模式 30 秒", op, e.getMessage());
    }
}
