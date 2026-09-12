package com.financial.copilot.agent.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.dag.guard.DefaultNodeQualityGate;
import com.financial.copilot.agent.core.dag.guard.NodeQualityGate;
import com.financial.copilot.agent.core.dag.runtime.ReplanPolicy;
import com.financial.copilot.agent.core.dag.runtime.checkpoint.DagCheckpointStore;
import com.financial.copilot.agent.core.dag.runtime.checkpoint.RedisDagCheckpointStore;
import com.financial.copilot.agent.core.dag.runtime.resource.ResourceManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * <h1>DAG 运行时 Spring 自动装配配置</h1>
 * <p>
 * 提供 {@link ResourceManager}、{@link DagCheckpointStore}、{@link NodeQualityGate}
 * 与 {@link ReplanPolicy} 等核心组件的 Bean 定义与优雅降级。
 * </p>
 */
@Configuration
public class DagRuntimeConfig {

    @Bean
    @ConditionalOnMissingBean
    public ResourceManager dagResourceManager() {
        return ResourceManager.defaultManager();
    }

    @Bean
    @ConditionalOnMissingBean
    public DagCheckpointStore dagCheckpointStore(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        return new RedisDagCheckpointStore(redisTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public NodeQualityGate dagNodeQualityGate() {
        return new DefaultNodeQualityGate();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReplanPolicy dagReplanPolicy() {
        return ReplanPolicy.heuristic();
    }
}
