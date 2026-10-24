package com.financial.copilot.agent.tools.cache;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * <h1>工具执行缓存切面 (Tool Cache Aspect)</h1>
 * <p>
 * 拦截标注了 {@link ToolCacheable} 的工具方法。
 * 在工具真实执行前计算规范化入参哈希，若缓存命中则直接返回缓存结果，杜绝重复计算与数据库查询；
 * 未命中时执行真实工具逻辑，并将成功结果自动写入缓存。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Aspect
@Component
@Order(10)
public class ToolCacheAspect {

    private final ToolExecutionCache cache;
    private final ToolCacheKeyGenerator keyGenerator;
    private final ToolCacheProperties properties;

    public ToolCacheAspect(
            ToolExecutionCache cache,
            ToolCacheKeyGenerator keyGenerator,
            ToolCacheProperties properties) {
        this.cache = cache;
        this.keyGenerator = keyGenerator;
        this.properties = properties != null ? properties : new ToolCacheProperties();
    }

    @Around("@annotation(toolCacheable)")
    public Object aroundToolExecution(ProceedingJoinPoint joinPoint, ToolCacheable toolCacheable) throws Throwable {
        if (!properties.isEnabled()) {
            return joinPoint.proceed();
        }

        String toolName = resolveToolName(joinPoint, toolCacheable);
        Object[] args = joinPoint.getArgs();
        String cacheKey = keyGenerator.generateKey(toolName, args);

        // 1. 检索缓存
        Optional<String> cached = cache.get(toolName, cacheKey);
        if (cached.isPresent()) {
            log.info("[TOOL-CACHE-HIT] 工具缓存命中: tool={}, key={}", toolName, cacheKey);
            return cached.get();
        }

        // 2. 缓存未命中，调用目标工具逻辑
        log.debug("[TOOL-CACHE-MISS] 缓存未命中，调用底层工具: tool={}, key={}", toolName, cacheKey);
        Object result = joinPoint.proceed();

        // 3. 结果写回缓存
        if (result instanceof String resultStr) {
            if (shouldCacheResult(resultStr, toolCacheable)) {
                cache.put(toolName, cacheKey, resultStr, toolCacheable.ttlSeconds());
            }
        }

        return result;
    }

    private String resolveToolName(ProceedingJoinPoint joinPoint, ToolCacheable toolCacheable) {
        if (!toolCacheable.value().isBlank()) {
            return toolCacheable.value().trim();
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    private boolean shouldCacheResult(String resultStr, ToolCacheable annotation) {
        if (resultStr == null || resultStr.isBlank()) {
            return false;
        }
        if (annotation.cacheErrors()) {
            return true;
        }
        // 若响应内容判定为业务错误，不进行缓存，以便下一次重试能够重新执行
        String lower = resultStr.toLowerCase();
        if (lower.contains("\"status\":\"error\"") || lower.contains("\"status\": \"error\"")) {
            return false;
        }
        if (lower.contains("\"error\":") && lower.contains("\"message\":")) {
            return false;
        }
        return true;
    }
}
