package com.financial.copilot.mcp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <h1>MCP 服务虚拟线程与响应式调度配置类</h1>
 * <p>
 * 提供基于 Java 21 虚拟线程的执行器与 Reactor 调度器，确保所有 MCP 工具的反射执行与非阻塞调度不占用操作系统平台线程。
 * </p>
 *
 * @author FinancialCopilot
 */
@Configuration
public class McpVirtualThreadConfig {

    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final Scheduler VIRTUAL_SCHEDULER = Schedulers.fromExecutor(VIRTUAL_EXECUTOR);

    /**
     * 注册 MCP 专用的虚拟线程响应式调度器 Bean。
     *
     * @return 虚拟线程 Reactor Scheduler
     */
    @Bean(name = "mcpVirtualThreadScheduler")
    @ConditionalOnMissingBean(name = "mcpVirtualThreadScheduler")
    public Scheduler mcpVirtualThreadScheduler() {
        return VIRTUAL_SCHEDULER;
    }

    /**
     * 注册 MCP 底层虚拟线程 ExecutorService Bean。
     *
     * @return 虚拟线程 ExecutorService
     */
    @Bean(name = "mcpVirtualThreadExecutor")
    @ConditionalOnMissingBean(name = "mcpVirtualThreadExecutor")
    public ExecutorService mcpVirtualThreadExecutor() {
        return VIRTUAL_EXECUTOR;
    }

    /**
     * 注册 Spring TaskExecutor 虚拟线程执行器 Bean。
     *
     * @return 虚拟线程 TaskExecutor
     */
    @Bean(name = "taskExecutor")
    @ConditionalOnMissingBean(name = "taskExecutor")
    public Executor taskExecutor() {
        return new VirtualThreadTaskExecutor("mcp-vt-");
    }

    /**
     * 提供全局静态获取调度器方法。
     *
     * @return 虚拟线程 Reactor Scheduler
     */
    public static Scheduler scheduler() {
        return VIRTUAL_SCHEDULER;
    }

    /**
     * 提供全局静态获取执行器方法。
     *
     * @return 虚拟线程 ExecutorService
     */
    public static ExecutorService executor() {
        return VIRTUAL_EXECUTOR;
    }
}
