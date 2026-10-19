package com.financial.copilot.agent.core.infra.supervisor;

import java.time.Duration;

/**
 * <h1>Supervisor 有界执行预算规格</h1>
 *
 * @param maxReviewCycles 最大审查/重试/重规划循环轮数
 * @param maxNodeAttempts 节点最大尝试次数
 * @param timeout 总运行超时时长
 * @param pointBudget 最大算力积分预算
 * @author FinancialCopilot
 */
public record SupervisorBudget(
        int maxReviewCycles,
        int maxNodeAttempts,
        Duration timeout,
        long pointBudget
) {
    public static SupervisorBudget defaults() {
        return new SupervisorBudget(3, 3, Duration.ofMinutes(5), 10_000L);
    }
}
