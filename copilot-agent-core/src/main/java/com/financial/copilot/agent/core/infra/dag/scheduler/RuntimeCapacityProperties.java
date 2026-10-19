package com.financial.copilot.agent.core.infra.dag.scheduler;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * <h1>DAG 运行时容量与并发调度配置属性</h1>
 * <p>
 * 统一管控单机活跃运行上限、全局排队容量、单用户运行限制、单 Run Agent 并发上限、物理资源配额与排队超时保护。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ConfigurationProperties(prefix = "copilot.runtime")
public class RuntimeCapacityProperties {

    /** 系统全局最大活跃 Run 运行数 */
    @Builder.Default
    private int maxActiveRuns = 100;

    /** 系统全局最大排队 Run 运行数 */
    @Builder.Default
    private int maxQueuedRuns = 1000;

    /** 单用户最大同时活跃 Run 运行数 */
    @Builder.Default
    private int maxActiveRunsPerUser = 2;

    /** 单用户最大排队 Run 运行数 */
    @Builder.Default
    private int maxQueuedRunsPerUser = 5;

    /** 单个 Run 执行图内最大并发 Agent 数量 */
    @Builder.Default
    private int maxAgentsPerRun = 3;

    /** 全局最大 Agent 并发许可数 */
    @Builder.Default
    private int maxAgentConcurrency = 32;

    /** 全局最大 RAG 检索并发许可数 */
    @Builder.Default
    private int maxRagConcurrency = 32;

    /** 队列等待最长超时时间 */
    @Builder.Default
    private Duration queueTimeout = Duration.ofMinutes(5);

    /** 全局最大 LLM 调用并发许可数 */
    @Builder.Default
    private int maxLlmConcurrency = 16;

    /** 单次 LLM 调用最长超时时间 */
    @Builder.Default
    private Duration llmCallTimeout = Duration.ofSeconds(120);

    /**
     * 创建默认配置属性实例
     *
     * @return 默认配置属性
     */
    public static RuntimeCapacityProperties defaults() {
        return new RuntimeCapacityProperties();
    }
}
