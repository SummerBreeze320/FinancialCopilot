package com.financial.copilot.agent.core.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;

/**
 * <h1>全局异步任务配置</h1>
 * <p>
 * 基于 Java 21 虚拟线程 (VirtualThreadTaskExecutor) 运行 Spring @Async 异步任务，消除传统定长线程池瓶颈。
 * </p>
 *
 * @author FinancialCopilot
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        return new VirtualThreadTaskExecutor("copilot-async-vt-");
    }
}
