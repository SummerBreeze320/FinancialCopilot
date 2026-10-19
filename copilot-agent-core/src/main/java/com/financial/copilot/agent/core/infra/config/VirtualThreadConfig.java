package com.financial.copilot.agent.core.infra.config;

import com.financial.copilot.agent.core.infra.thread.VirtualThreadSchedulers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import reactor.core.scheduler.Scheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * <h1>虚拟线程与响应式调度 Spring 配置类</h1>
 * <p>
 * 向 Spring 容器统一注册虚拟线程响应式调度器 ({@link Scheduler}) 与异步任务执行器 ({@link Executor})，
 * 使 WebFlux 流式调用、耗时计算与 Spring 异步任务无缝运行于 Java 21 虚拟线程上。
 * </p>
 *
 * @author FinancialCopilot
 */
@Configuration
public class VirtualThreadConfig {

    /**
     * 注册 Reactor 响应式虚拟线程调度器 Bean。
     *
     * @return 响应式 Scheduler Bean
     */
    @Bean(name = "virtualThreadScheduler")
    @ConditionalOnMissingBean(name = "virtualThreadScheduler")
    public Scheduler virtualThreadScheduler() {
        return VirtualThreadSchedulers.scheduler();
    }

    /**
     * 注册虚拟线程底层 ExecutorService Bean。
     *
     * @return 虚拟线程 ExecutorService
     */
    @Bean(name = "virtualThreadExecutor")
    @ConditionalOnMissingBean(name = "virtualThreadExecutor")
    public ExecutorService virtualThreadExecutor() {
        return VirtualThreadSchedulers.executor();
    }
}
