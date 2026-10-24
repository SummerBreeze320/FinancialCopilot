package com.financial.copilot.gateway.transport;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <h1>网关虚拟线程响应式调度器管理器</h1>
 * <p>
 * 提供基于 Java 21 Project Loom 虚拟线程 (Virtual Threads) 的 Reactor {@link Scheduler} 单例封装，
 * 消除高并发网络 I/O 场景下的平台线程阻塞风险。
 * </p>
 *
 * @author FinancialCopilot
 */
public final class GatewaySchedulers {

    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final Scheduler VIRTUAL_SCHEDULER = Schedulers.fromExecutor(VIRTUAL_EXECUTOR);

    private GatewaySchedulers() {}

    /**
     * 获取全局共享的虚拟线程 Reactor 响应式调度器。
     *
     * @return 虚拟线程 Reactor Scheduler 实例
     */
    public static Scheduler scheduler() {
        return VIRTUAL_SCHEDULER;
    }

    /**
     * 获取全局共享的虚拟线程底层执行器。
     *
     * @return 虚拟线程 ExecutorService 实例
     */
    public static ExecutorService executor() {
        return VIRTUAL_EXECUTOR;
    }
}
