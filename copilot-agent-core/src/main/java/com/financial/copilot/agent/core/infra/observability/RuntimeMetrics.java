package com.financial.copilot.agent.core.infra.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * <h1>投研运行时指标观测器 (Runtime Metrics)</h1>
 * <p>
 * 集中管理投研 Agent 运行时的低基数核心观测指标，严格杜绝 userId、conversationId 或 runId 等高基数维度入标。
 * 覆盖活跃/排队运行数、排队等待耗时、节点耗时与取消、模型并发与调用错误、以及分布式租约接管。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class RuntimeMetrics {

    public static final String METRIC_RUNS_ACTIVE = "copilot.runtime.runs.active";
    public static final String METRIC_RUNS_QUEUED = "copilot.runtime.runs.queued";
    public static final String METRIC_QUEUE_WAIT = "copilot.runtime.queue.wait";
    public static final String METRIC_NODES_DURATION = "copilot.runtime.nodes.duration";
    public static final String METRIC_NODES_CANCELLED = "copilot.runtime.nodes.cancelled";
    public static final String METRIC_LLM_ACTIVE = "copilot.runtime.llm.active";
    public static final String METRIC_LLM_ERRORS = "copilot.runtime.llm.errors";
    public static final String METRIC_LEASES_TAKEOVER = "copilot.runtime.leases.takeover";

    private final MeterRegistry registry;

    private final AtomicInteger activeRunsGauge = new AtomicInteger(0);
    private final AtomicInteger queuedRunsGauge = new AtomicInteger(0);
    private final AtomicInteger activeLlmGauge = new AtomicInteger(0);

    private final Timer queueWaitTimer;
    private final Counter nodesCancelledCounter;
    private final Counter leasesTakeoverCounter;

    private final ConcurrentHashMap<String, Timer> nodeDurationTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> llmErrorCounters = new ConcurrentHashMap<>();

    @Autowired
    public RuntimeMetrics(@Autowired(required = false) MeterRegistry registry) {
        this.registry = registry != null ? registry : new SimpleMeterRegistry();

        Gauge.builder(METRIC_RUNS_ACTIVE, activeRunsGauge, AtomicInteger::get)
                .description("当前活跃运行中的投研任务总数")
                .register(this.registry);

        Gauge.builder(METRIC_RUNS_QUEUED, queuedRunsGauge, AtomicInteger::get)
                .description("当前在系统准入队列中等待调度的投研任务总数")
                .register(this.registry);

        Gauge.builder(METRIC_LLM_ACTIVE, activeLlmGauge, AtomicInteger::get)
                .description("当前正在执行模型调用的并发请求总数")
                .register(this.registry);

        this.queueWaitTimer = Timer.builder(METRIC_QUEUE_WAIT)
                .description("投研任务在准入队列中的等待耗时")
                .register(this.registry);

        this.nodesCancelledCounter = Counter.builder(METRIC_NODES_CANCELLED)
                .description("图节点被取消执行的总次数")
                .register(this.registry);

        this.leasesTakeoverCounter = Counter.builder(METRIC_LEASES_TAKEOVER)
                .description("分布式节点租约超时后被其他 Worker 抢占接管的总次数")
                .register(this.registry);
    }

    public RuntimeMetrics() {
        this(new SimpleMeterRegistry());
    }

    /**
     * 绑定活跃运行数动态提供函数
     */
    public void bindActiveRunsSupplier(Supplier<Number> supplier) {
        if (supplier == null) return;
        Gauge.builder(METRIC_RUNS_ACTIVE, supplier, s -> s.get().doubleValue())
                .description("当前活跃运行中的投研任务总数")
                .register(registry);
    }

    /**
     * 绑定排队等待运行数动态提供函数
     */
    public void bindQueuedRunsSupplier(Supplier<Number> supplier) {
        if (supplier == null) return;
        Gauge.builder(METRIC_RUNS_QUEUED, supplier, s -> s.get().doubleValue())
                .description("当前在系统准入队列中等待调度的投研任务总数")
                .register(registry);
    }

    /**
     * 绑定活跃 LLM 并发调用数动态提供函数
     */
    public void bindActiveLlmSupplier(Supplier<Number> supplier) {
        if (supplier == null) return;
        Gauge.builder(METRIC_LLM_ACTIVE, supplier, s -> s.get().doubleValue())
                .description("当前正在执行模型调用的并发请求总数")
                .register(registry);
    }

    public void setActiveRuns(int count) {
        activeRunsGauge.set(count);
    }

    public void setQueuedRuns(int count) {
        queuedRunsGauge.set(count);
    }

    public void setActiveLlm(int count) {
        activeLlmGauge.set(count);
    }

    public void recordQueueWait(Duration duration) {
        if (duration != null && !duration.isNegative()) {
            queueWaitTimer.record(duration);
        }
    }

    public void recordNodeDuration(String role, Duration duration) {
        if (duration != null && !duration.isNegative()) {
            String cleanRole = role != null && !role.isBlank() ? role.trim().toLowerCase() : "unknown";
            nodeDurationTimers.computeIfAbsent(cleanRole, r -> Timer.builder(METRIC_NODES_DURATION)
                    .tag("role", r)
                    .description("图节点执行耗时分布")
                    .register(registry))
                    .record(duration);
        }
    }

    public void recordNodeCancelled() {
        nodesCancelledCounter.increment();
    }

    public void recordLlmError(String provider, String errorType) {
        String cleanProvider = provider != null && !provider.isBlank() ? provider.trim().toUpperCase() : "UNKNOWN";
        String cleanError = errorType != null && !errorType.isBlank() ? errorType.trim() : "Exception";
        String key = cleanProvider + ":" + cleanError;
        llmErrorCounters.computeIfAbsent(key, k -> Counter.builder(METRIC_LLM_ERRORS)
                .tag("provider", cleanProvider)
                .tag("error", cleanError)
                .description("大模型执行网关调用失败异常计数")
                .register(registry))
                .increment();
    }

    public void recordLeaseTakeover() {
        leasesTakeoverCounter.increment();
    }

    public MeterRegistry registry() {
        return registry;
    }
}
