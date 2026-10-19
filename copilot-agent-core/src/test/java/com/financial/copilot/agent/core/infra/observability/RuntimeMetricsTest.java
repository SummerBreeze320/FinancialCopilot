package com.financial.copilot.agent.core.infra.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeMetricsTest {

    private SimpleMeterRegistry registry;
    private RuntimeMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new RuntimeMetrics(registry);
    }

    @Test
    void tracksActiveAndQueuedRunsGauges() {
        metrics.setActiveRuns(5);
        metrics.setQueuedRuns(12);
        metrics.setActiveLlm(3);

        Gauge activeRuns = registry.find(RuntimeMetrics.METRIC_RUNS_ACTIVE).gauge();
        assertThat(activeRuns).isNotNull();
        assertThat(activeRuns.value()).isEqualTo(5.0);

        Gauge queuedRuns = registry.find(RuntimeMetrics.METRIC_RUNS_QUEUED).gauge();
        assertThat(queuedRuns).isNotNull();
        assertThat(queuedRuns.value()).isEqualTo(12.0);

        Gauge activeLlm = registry.find(RuntimeMetrics.METRIC_LLM_ACTIVE).gauge();
        assertThat(activeLlm).isNotNull();
        assertThat(activeLlm.value()).isEqualTo(3.0);
    }

    @Test
    void recordsQueueWaitDuration() {
        metrics.recordQueueWait(Duration.ofMillis(350));
        metrics.recordQueueWait(Duration.ofMillis(150));

        Timer timer = registry.find(RuntimeMetrics.METRIC_QUEUE_WAIT).timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(500.0);
    }

    @Test
    void recordsNodeDurationWithBoundedRoleTag() {
        metrics.recordNodeDuration("fund_analyst", Duration.ofMillis(1200));
        metrics.recordNodeDuration("fund_analyst", Duration.ofMillis(800));

        Timer timer = registry.find(RuntimeMetrics.METRIC_NODES_DURATION)
                .tag("role", "fund_analyst")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(2);
    }

    @Test
    void recordsNodeCancelledCounter() {
        metrics.recordNodeCancelled();
        metrics.recordNodeCancelled();

        Counter counter = registry.find(RuntimeMetrics.METRIC_NODES_CANCELLED).counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void recordsLlmErrorWithBoundedTags() {
        metrics.recordLlmError("DEEPSEEK", "TimeoutException");

        Counter counter = registry.find(RuntimeMetrics.METRIC_LLM_ERRORS)
                .tag("provider", "DEEPSEEK")
                .tag("error", "TimeoutException")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordsLeaseTakeoverCounter() {
        metrics.recordLeaseTakeover();
        metrics.recordLeaseTakeover();
        metrics.recordLeaseTakeover();

        Counter counter = registry.find(RuntimeMetrics.METRIC_LEASES_TAKEOVER).counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(3.0);
    }
}
