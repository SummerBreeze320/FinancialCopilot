package com.financial.copilot.agent.core.dag.runtime.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceManagerTest {

    @Test
    void defaultManagerLimitsConcurrentAgents() {
        ResourceManager manager = ResourceManager.defaultManager();

        assertThat(manager.getAvailablePermits(ResourceType.AGENT)).isEqualTo(8);
    }

    @Test
    @DisplayName("验证默认配额初始化")
    void testDefaultQuotaInitialization() {
        ResourceManager manager = ResourceManager.defaultManager();
        assertThat(manager.getAvailablePermits(ResourceType.LLM)).isEqualTo(4);
        assertThat(manager.getAvailablePermits(ResourceType.DPU)).isEqualTo(10);
        assertThat(manager.getAvailablePermits(ResourceType.RAG)).isEqualTo(20);
        assertThat(manager.getAvailablePermits(ResourceType.MCP)).isEqualTo(10);
        assertThat(manager.getAvailablePermits(ResourceType.COMPONENT)).isEqualTo(8);
    }

    @Test
    @DisplayName("验证 tryAcquire 成功与超额拒绝")
    void testTryAcquireAndReject() {
        ResourceManager manager = new ResourceManager(Map.of(ResourceType.LLM, 2));

        ResourceRequirement req = ResourceRequirement.of(ResourceType.LLM, 1);
        assertThat(manager.tryAcquire(req)).isTrue();
        assertThat(manager.getAvailablePermits(ResourceType.LLM)).isEqualTo(1);

        assertThat(manager.tryAcquire(req)).isTrue();
        assertThat(manager.getAvailablePermits(ResourceType.LLM)).isEqualTo(0);

        // 配额已耗尽，再次 tryAcquire 应返回 false
        assertThat(manager.tryAcquire(req)).isFalse();

        // 释放 1 个许可后，再次 tryAcquire 成功
        manager.release(req);
        assertThat(manager.getAvailablePermits(ResourceType.LLM)).isEqualTo(1);
        assertThat(manager.tryAcquire(req)).isTrue();
    }

    @Test
    @DisplayName("验证多虚拟线程并发争抢配额时的封顶控制与释放唤醒")
    void testVirtualThreadConcurrencyAndRelease() throws Exception {
        int maxLlmPermits = 3;
        ResourceManager manager = new ResourceManager(Map.of(ResourceType.LLM, maxLlmPermits));

        int totalTasks = 10;
        AtomicInteger runningConcurrent = new AtomicInteger(0);
        AtomicInteger maxObservedConcurrent = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(totalTasks);

        try (ExecutorService vThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < totalTasks; i++) {
                vThreads.submit(() -> {
                    ResourceRequirement req = ResourceRequirement.of(ResourceType.LLM, 1);
                    try {
                        manager.acquire(req);
                        int current = runningConcurrent.incrementAndGet();
                        maxObservedConcurrent.accumulateAndGet(current, Math::max);

                        // 模拟短暂计算 I/O
                        Thread.sleep(50);

                        runningConcurrent.decrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        manager.release(req);
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(maxObservedConcurrent.get()).isLessThanOrEqualTo(maxLlmPermits);
            assertThat(manager.getAvailablePermits(ResourceType.LLM)).isEqualTo(maxLlmPermits);
        }
    }

    @Test
    void multipleRequirementsAcquireAllOrNothing() {
        ResourceManager manager = new ResourceManager(Map.of(ResourceType.LLM, 1, ResourceType.DPU, 0));
        Map<ResourceType, Integer> requirements = Map.of(ResourceType.LLM, 1, ResourceType.DPU, 1);

        assertThat(manager.tryAcquire(requirements)).isFalse();
        assertThat(manager.getAvailablePermits(ResourceType.LLM)).isEqualTo(1);

        ResourceManager available = new ResourceManager(Map.of(ResourceType.LLM, 1, ResourceType.DPU, 1));
        assertThat(available.tryAcquire(requirements)).isTrue();
        available.release(requirements);
        assertThat(available.getAvailablePermits(ResourceType.LLM)).isEqualTo(1);
        assertThat(available.getAvailablePermits(ResourceType.DPU)).isEqualTo(1);
    }
}
