package com.financial.copilot.agent.core.infra.supervisor.admission;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * <h1>运行时并发容量与准入配置属性</h1>
 * <p>
 * 控制每个会话的串行度（防并发重入破坏上下文）、单用户并发上限与系统全局并发负载。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Component
@ConfigurationProperties(prefix = "copilot.supervisor.capacity")
public class RuntimeCapacityProperties {

    /**
     * 单个会话最大允许并发执行的 Run 数量，默认为 1（强制会话串行执行）。
     */
    @Builder.Default
    private int maxActiveRunsPerConversation = 1;

    /**
     * 单个用户最大允许并发执行的 Run 数量（跨会话）。
     */
    @Builder.Default
    private int maxActiveRunsPerUser = 5;

    /**
     * 系统全局最大允许并发执行的 Run 数量。
     */
    @Builder.Default
    private int maxActiveRunsGlobal = 20;

    /**
     * 排队获取准入许可的最大等待超时毫秒数，默认 0 即不排队直接拒绝。
     */
    @Builder.Default
    private long acquireTimeoutMs = 0L;

    /**
     * 系统排队 Run 上限。
     */
    @Builder.Default
    private int maxQueuedRuns = 20;

    /**
     * 系统最大 LLM 并发调用数。
     */
    @Builder.Default
    private int maxLlmConcurrency = 16;

    public int getMaxActiveRuns() {
        return maxActiveRunsGlobal;
    }
}

