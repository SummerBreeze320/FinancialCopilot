package com.financial.copilot.gateway.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <h1>网关响应式并发限流器 (GatewayCapacityLimiter)</h1>
 * <p>基于非阻塞响应式 FIFO 队列实现的并发配额控制，保护下游 LLM 服务免受高并发压垮。</p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class GatewayCapacityLimiter {

    private final int maxCapacity;
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final Queue<Sinks.One<CapacityPermit>> waitQueue = new ConcurrentLinkedQueue<>();

    public GatewayCapacityLimiter() {
        this(16);
    }

    public GatewayCapacityLimiter(int maxCapacity) {
        this.maxCapacity = Math.max(1, maxCapacity);
    }

    /**
     * 响应式非阻塞获取并发许可证
     *
     * @return 包含许可证的 Mono，在容量不足时自动挂起排队
     */
    public Mono<CapacityPermit> acquire() {
        return Mono.defer(() -> {
            while (true) {
                int current = activeCount.get();
                if (current < maxCapacity) {
                    if (activeCount.compareAndSet(current, current + 1)) {
                        DefaultCapacityPermit permit = new DefaultCapacityPermit(this);
                        return Mono.just((CapacityPermit) permit)
                                .doOnCancel(permit::release);
                    }
                } else {
                    Sinks.One<CapacityPermit> sink = Sinks.one();
                    waitQueue.offer(sink);

                    // 二次校验，防止入队瞬间前置许可证已释放导致的死锁
                    if (activeCount.get() < maxCapacity) {
                        drainQueue();
                    }

                    return sink.asMono()
                            .doOnCancel(() -> waitQueue.remove(sink));
                }
            }
        });
    }

    /**
     * 当前空闲可用许可证数量
     *
     * @return 可用许可证数
     */
    public int availablePermits() {
        int available = maxCapacity - activeCount.get();
        return Math.max(0, available);
    }

    /**
     * 最大并发容量
     *
     * @return 最大许可数
     */
    public int maxCapacity() {
        return maxCapacity;
    }

    private void releasePermit() {
        drainQueue();
    }

    private void drainQueue() {
        while (true) {
            Sinks.One<CapacityPermit> nextSink = waitQueue.poll();
            if (nextSink != null) {
                Sinks.EmitResult result = nextSink.tryEmitValue(new DefaultCapacityPermit(this));
                if (result == Sinks.EmitResult.OK) {
                    return;
                }
                // 若该订阅者已取消，继续寻找下一个等待者
            } else {
                activeCount.decrementAndGet();
                // 再次检查是否有并发新加入排队者
                if (!waitQueue.isEmpty() && activeCount.get() < maxCapacity) {
                    if (activeCount.incrementAndGet() <= maxCapacity) {
                        continue;
                    } else {
                        activeCount.decrementAndGet();
                    }
                }
                return;
            }
        }
    }

    /**
     * 许可证接口定义
     */
    public interface CapacityPermit extends AutoCloseable {
        void release();

        @Override
        default void close() {
            release();
        }
    }

    private static class DefaultCapacityPermit implements CapacityPermit {
        private final GatewayCapacityLimiter limiter;
        private final AtomicBoolean released = new AtomicBoolean(false);

        DefaultCapacityPermit(GatewayCapacityLimiter limiter) {
            this.limiter = limiter;
        }

        @Override
        public void release() {
            if (released.compareAndSet(false, true)) {
                limiter.releasePermit();
            }
        }
    }
}
