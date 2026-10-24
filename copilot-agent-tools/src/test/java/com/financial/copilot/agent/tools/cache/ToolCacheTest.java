package com.financial.copilot.agent.tools.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>工具执行缓存系统单元测试 (Tool Cache Test)</h1>
 * <p>
 * 验证：
 * 1. 规范化键生成一致性（同参同键，无序 Map 强归一化）
 * 2. 一级内存缓存（Put、Get、Evict、Clear、Stats）
 * 3. AOP 切面拦截（命中跳过执行、不同参数透传、错误不缓存）
 * </p>
 *
 * @author FinancialCopilot
 */
class ToolCacheTest {

    private ToolCacheKeyGenerator keyGenerator;
    private CaffeineToolExecutionCache cache;
    private ToolCacheProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ToolCacheProperties();
        properties.setEnabled(true);
        properties.setL1MaxSize(1000);
        properties.setDefaultTtlSeconds(300);
        properties.setRedisEnabled(false); // 单元测试中隔离 Redis

        keyGenerator = new ToolCacheKeyGenerator();
        cache = new CaffeineToolExecutionCache(properties, null);
    }

    @Test
    @DisplayName("键生成器：相同入参应生成确定性缓存键，无序 Map 经字典序重排后键一致")
    void testCanonicalKeyGenerationConsistency() {
        Map<String, Object> map1 = new LinkedHashMap<>();
        map1.put("code", "005827");
        map1.put("period", "1Y");

        Map<String, Object> map2 = new LinkedHashMap<>();
        map2.put("period", "1Y");
        map2.put("code", "005827");

        String key1 = keyGenerator.generateKey("fund_metrics", new Object[]{map1});
        String key2 = keyGenerator.generateKey("fund_metrics", new Object[]{map2});

        assertThat(key1).isNotNull().isEqualTo(key2);
        assertThat(key1).startsWith("tool:cache:fund_metrics:");
    }

    @Test
    @DisplayName("缓存读写：基础 Put/Get/Evict/Clear 与 Stats 观测指标")
    void testCacheBasicOperationsAndStats() {
        String toolName = "fund_profile";
        String key = "tool:cache:fund_profile:test_key";
        String payload = "{\"fundCode\":\"005827\",\"name\":\"易方达蓝筹精选\"}";

        // 1. 初始未命中
        Optional<String> initial = cache.get(toolName, key);
        assertThat(initial).isEmpty();

        // 2. 写入缓存
        cache.put(toolName, key, payload, 60);

        // 3. 读取命中
        Optional<String> hit = cache.get(toolName, key);
        assertThat(hit).isPresent().contains(payload);

        // 4. 淘汰
        cache.evict(toolName, key);
        Optional<String> afterEvict = cache.get(toolName, key);
        assertThat(afterEvict).isEmpty();

        // 5. 再次写入并全量清空工具
        cache.put(toolName, key, payload, 60);
        cache.clear(toolName);
        assertThat(cache.get(toolName, key)).isEmpty();

        // 6. 验证统计指标
        ToolCacheStats stats = cache.getStats();
        assertThat(stats.getHitCount()).isGreaterThanOrEqualTo(1);
        assertThat(stats.getMissCount()).isGreaterThanOrEqualTo(2);
        assertThat(stats.getPutCount()).isGreaterThanOrEqualTo(2);
    }

    /**
     * 测试用假工具服务
     */
    static class MockQuantTool {
        final AtomicInteger executionCount = new AtomicInteger(0);

        @ToolCacheable(value = "mock_metrics", ttlSeconds = 60)
        public String calculateMetrics(String fundCode, String period) {
            executionCount.incrementAndGet();
            if ("ERROR_CODE".equals(fundCode)) {
                return "{\"status\": \"ERROR\", \"message\": \"标的不存在\"}";
            }
            return "{\"fundCode\":\"" + fundCode + "\",\"period\":\"" + period + "\",\"sharpe\":1.85}";
        }
    }

    @Test
    @DisplayName("AOP 切面：同一工具同参重复调用直接查缓存，避免底层多次计算")
    void testToolCacheAspectMemoization() {
        MockQuantTool target = new MockQuantTool();
        ToolCacheAspect aspect = new ToolCacheAspect(cache, keyGenerator, properties);

        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        MockQuantTool proxy = factory.getProxy();

        // 第一次调用：未命中缓存，调用底层方法
        String res1 = proxy.calculateMetrics("005827", "1Y");
        assertThat(res1).contains("005827").contains("1.85");
        assertThat(target.executionCount.get()).isEqualTo(1);

        // 第二次调用（相同参数）：命中缓存，直接返回，底层执行计数仍为 1
        String res2 = proxy.calculateMetrics("005827", "1Y");
        assertThat(res2).isEqualTo(res1);
        assertThat(target.executionCount.get()).isEqualTo(1);

        // 第三次调用（不同参数）：缓存未命中，调用底层方法，执行计数递增为 2
        String res3 = proxy.calculateMetrics("161005", "1Y");
        assertThat(res3).contains("161005");
        assertThat(target.executionCount.get()).isEqualTo(2);

        // 验证缓存命中统计
        ToolCacheStats stats = cache.getStats();
        assertThat(stats.getHitCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("AOP 切面：失败响应 (status=ERROR) 默认不缓存，确保重试穿透")
    void testErrorResultsNotCached() {
        MockQuantTool target = new MockQuantTool();
        ToolCacheAspect aspect = new ToolCacheAspect(cache, keyGenerator, properties);

        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        MockQuantTool proxy = factory.getProxy();

        // 第一次调用返回错误
        String err1 = proxy.calculateMetrics("ERROR_CODE", "1Y");
        assertThat(err1).contains("ERROR");
        assertThat(target.executionCount.get()).isEqualTo(1);

        // 第二次相同错误入参调用：由于错误未缓存，再次穿透到底层方法
        String err2 = proxy.calculateMetrics("ERROR_CODE", "1Y");
        assertThat(err2).contains("ERROR");
        assertThat(target.executionCount.get()).isEqualTo(2);
    }
}
