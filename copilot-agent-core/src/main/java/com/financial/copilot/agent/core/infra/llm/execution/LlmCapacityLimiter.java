package com.financial.copilot.agent.core.infra.llm.execution;

import com.financial.copilot.agent.core.infra.thread.VirtualThreadSchedulers;
import reactor.core.publisher.Mono;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <h1>全局 LLM 调用容量限制器</h1>
 * 严格控制对下游大模型 Provider 的并发请求数量，避免触发供应商限流或造成单机过载。
 */
public class LlmCapacityLimiter {

    private final int maxConcurrency;
    private final Semaphore semaphore;
    private final AtomicInteger currentConcurrency = new AtomicInteger(0);
    private final AtomicInteger maxObserved = new AtomicInteger(0);

    public LlmCapacityLimiter(int maxConcurrency) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be positive: " + maxConcurrency);
        }
        this.maxConcurrency = maxConcurrency;
        this.semaphore = new Semaphore(maxConcurrency, true);
    }

    public Mono<Permit> acquireReactive() {
        return Mono.fromCallable(() -> {
            semaphore.acquire();
            int active = currentConcurrency.incrementAndGet();
            maxObserved.updateAndGet(cur -> Math.max(cur, active));
            return new Permit();
        }).subscribeOn(VirtualThreadSchedulers.scheduler());
    }

    public int availablePermits() {
        return semaphore.availablePermits();
    }

    public int maxObservedConcurrency() {
        return maxObserved.get();
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }

    public final class Permit implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                currentConcurrency.decrementAndGet();
                semaphore.release();
            }
        }
    }
}
