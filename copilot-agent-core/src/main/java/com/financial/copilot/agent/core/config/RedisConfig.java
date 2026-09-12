package com.financial.copilot.agent.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * <h1>Redis 配置类</h1>
 *
 * <p>
 * 为记忆服务提供字符串 Redis 操作；连接由 Spring Boot 自动配置。
 * 使用 {@link StringRedisTemplate} 操作普通字符串键值对，
 * 并通过 Sorted Set (ZSET) 保存每一次记忆的时间戳，以便快速获取最新记录。
 * </p>
 *
 * @author FinancialCopilot
 */
@Configuration
public class RedisConfig {

    /**
     * 暴露 StringRedisTemplate，便于在业务代码中直接使用。
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
