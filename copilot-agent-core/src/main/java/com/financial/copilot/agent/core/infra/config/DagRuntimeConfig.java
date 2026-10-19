package com.financial.copilot.agent.core.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.infra.dag.guard.DefaultNodeQualityGate;
import com.financial.copilot.agent.core.infra.dag.guard.NodeQualityGate;
import com.financial.copilot.agent.core.infra.dag.runtime.ReplanPolicy;
import com.financial.copilot.agent.core.infra.dag.runtime.checkpoint.DagCheckpointStore;
import com.financial.copilot.agent.core.infra.dag.runtime.checkpoint.RedisDagCheckpointStore;
import com.financial.copilot.agent.core.infra.dag.runtime.resource.ResourceManager;
import com.financial.copilot.agent.core.infra.dag.runtime.resource.ResourceType;
import com.financial.copilot.agent.core.infra.dag.scheduler.GlobalReadyScheduler;
import com.financial.copilot.agent.core.infra.dag.scheduler.RunAdmissionController;
import com.financial.copilot.agent.core.infra.dag.scheduler.RuntimeCapacityProperties;
import com.financial.copilot.agent.core.infra.llm.execution.LlmCapacityLimiter;
import com.financial.copilot.agent.core.infra.observability.RuntimeMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import com.financial.copilot.agent.core.business.agents.AgentNodeRouter;
import com.financial.copilot.agent.core.infra.dag.planner.GraphPlannerAgent;
import com.financial.copilot.agent.core.infra.dag.runtime.DagRuntime;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutor;
import com.financial.copilot.domain.platform.execution.port.ResearchExecutionPort;

/**
 * <h1>DAG 运行时 Spring 自动装配配置</h1>
 * <p>
 * 提供 {@link ResourceManager}、{@link GlobalReadyScheduler}、{@link RunAdmissionController}、
 * {@link DagCheckpointStore}、{@link NodeQualityGate} 与 {@link ReplanPolicy} 等核心组件的 Bean 定义与优雅降级。
 * </p>
 */
@Configuration
@EnableConfigurationProperties(RuntimeCapacityProperties.class)
public class DagRuntimeConfig {

    @Bean
    @ConditionalOnMissingBean
    public ResourceManager dagResourceManager(RuntimeCapacityProperties properties) {
        return new ResourceManager(Map.of(
                ResourceType.AGENT, properties.getMaxAgentConcurrency(),
                ResourceType.LLM, properties.getMaxLlmConcurrency(),
                ResourceType.DPU, 10,
                ResourceType.RAG, properties.getMaxRagConcurrency(),
                ResourceType.MCP, 10,
                ResourceType.COMPONENT, 8
        ));
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalReadyScheduler globalReadyScheduler(ResourceManager resourceManager, RuntimeCapacityProperties properties,
            @Autowired(required = false) RuntimeMetrics runtimeMetrics) {
        return new GlobalReadyScheduler(resourceManager, properties, runtimeMetrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public RunAdmissionController runAdmissionController(RuntimeCapacityProperties properties,
            @Autowired(required = false) RuntimeMetrics runtimeMetrics) {
        return new RunAdmissionController(properties, runtimeMetrics);
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

    @Bean
    @ConditionalOnMissingBean
    public LlmCapacityLimiter llmCapacityLimiter(RuntimeCapacityProperties properties) {
        return new LlmCapacityLimiter(properties.getMaxLlmConcurrency());
    }

    @Bean
    @ConditionalOnMissingBean
    public DagRuntime dagRuntime(
            AgentNodeRouter agentRouter,
            ResourceManager resourceManager,
            DagCheckpointStore checkpointStore,
            NodeQualityGate qualityGate,
            ReplanPolicy replanPolicy,
            GraphPlannerAgent planner,
            GlobalReadyScheduler globalReadyScheduler,
            RunAdmissionController admissionController,
            @Autowired(required = false) ResearchExecutionPort executionPort,
            @Autowired(required = false) RuntimeMetrics runtimeMetrics
    ) {
        NodeExecutor dispatcher = (node, input, context) -> {
            Instant start = Instant.now();
            try {
                return agentRouter.execute(node, input, context);
            } finally {
                if (runtimeMetrics != null && node != null) {
                    runtimeMetrics.recordNodeDuration(node.getTaskType(), Duration.between(start, Instant.now()));
                }
            }
        };
        return new DagRuntime(dispatcher, resourceManager, checkpointStore, qualityGate,
                replanPolicy, planner, globalReadyScheduler, admissionController, executionPort);
    }
}

