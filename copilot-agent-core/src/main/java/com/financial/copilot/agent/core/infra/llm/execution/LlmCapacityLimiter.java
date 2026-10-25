package com.financial.copilot.agent.core.infra.llm.execution;

import com.financial.copilot.gateway.governance.GatewayCapacityLimiter;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <h1>全局 LLM 调用容量限制器适配器 (LlmCapacityLimiter)</h1>
 * <p>
 * 统一委托至 {@link GatewayCapacityLimiter}，消除 DAG 运行时与网关层的双重限流与配额竞争。
 * </p>
 *
 * @author FinancialCopilot
 */
public class LlmCapacityLimiter {

    private final GatewayCapacityLimiter delegate;
    private final AtomicInteger currentConcurrency = new AtomicInteger(0);
    private final AtomicInteger maxObserved = new AtomicInteger(0);

    public LlmCapacityLimiter(int maxConcurrency) {
        this.delegate = new GatewayCapacityLimiter(maxConcurrency);
    }

    public LlmCapacityLimiter(GatewayCapacityLimiter delegate) {
        this.delegate = delegate != null ? delegate : new GatewayCapacityLimiter(16);
    }

    public Permit acquire() {
        try {
            semaphore.acquire();
            int active = currentConcurrency.incrementAndGet();
            maxObserved.updateAndGet(cur -> Math.max(cur, active));
            return new Permit();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring LLM capacity permit", e);
        }
    }

    public Mono<Permit> acquireReactive() {
        return delegate.acquire()
                .map(gatewayPermit -> {
                    int active = currentConcurrency.incrementAndGet();
                    maxObserved.updateAndGet(cur -> Math.max(cur, active));
                    return new Permit(gatewayPermit);
                })
                .doOnDiscard(GatewayCapacityLimiter.CapacityPermit.class, GatewayCapacityLimiter.CapacityPermit::release)
                .doOnDiscard(Permit.class, Permit::close);
    }

    public int availablePermits() {
        return delegate.availablePermits();
    }

    public int maxObservedConcurrency() {
        return maxObserved.get();
    }

    public int maxConcurrency() {
        return delegate.maxCapacity();
    }

    public final class Permit implements AutoCloseable {
        private final GatewayCapacityLimiter.CapacityPermit gatewayPermit;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        public Permit(GatewayCapacityLimiter.CapacityPermit gatewayPermit) {
            this.gatewayPermit = gatewayPermit;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                currentConcurrency.decrementAndGet();
                if (gatewayPermit != null) {
                    gatewayPermit.close();
                }
            }
        }
    }
}
