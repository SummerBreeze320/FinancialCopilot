package com.financial.copilot.agent.core.infra.agentscope.loop;

import lombok.Getter;

/**
 * <h1>Agent 终止条件与状态机状态 (AgentTerminationStatus)</h1>
 * <p>
 * 遵循 Agent 终止条件完备分类设计：
 * <ul>
 *     <li><b>a) 任务完成判定 (TASK_COMPLETED)</b>：模型判断目标达成，返回最终答复；</li>
 *     <li><b>b) 无可用下一步动作 (NO_AVAILABLE_ACTION)</b>：候选工具均不可用或信息不足，无法继续推演；</li>
 *     <li><b>c) 达到最大轮数 (MAX_ITERATIONS_REACHED)</b>：达到硬上限兜底收敛；</li>
 *     <li><b>d) 不可恢复错误 (UNRECOVERABLE_ERROR)</b>：工具持续失败熔断 (TOOL_CIRCUIT_BROKEN)、权限拒绝 (PERMISSION_DENIED)；</li>
 *     <li><b>附加防死循环保护</b>：连续重复入参调用 (CONSECUTIVE_DUPLICATE_CALL)、往复振荡死锁 (PING_PONG_OSCILLATION)。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Getter
public enum AgentTerminationStatus {

    /**
     * a) 任务完成判定：模型判断目标达成，正常输出最终答案
     */
    TASK_COMPLETED("任务完成判定", false),

    /**
     * b) 无可用下一步动作：所有工具均不可用或无可用动作推进
     */
    NO_AVAILABLE_ACTION("无可用下一步动作", true),

    /**
     * c) 达到最大轮数：触发单节点迭代硬上限兜底
     */
    MAX_ITERATIONS_REACHED("达到最大轮数硬上限", true),

    /**
     * d1) 不可恢复错误：工具持续失败触发熔断
     */
    TOOL_CIRCUIT_BROKEN("工具持续失败熔断", true),

    /**
     * d2) 不可恢复错误：权限拒绝或越权访问，无法通过重试自愈
     */
    PERMISSION_DENIED("权限拒绝不可恢复错误", true),

    /**
     * 防死循环保护：连续完全相同入参重复调用相同工具
     */
    CONSECUTIVE_DUPLICATE_CALL("连续重复入参死循环", true),

    /**
     * 防死循环保护：工具间周期性往复振荡死锁 (如 A -> B -> A -> B)
     */
    PING_PONG_OSCILLATION("往复振荡死锁", true),

    /**
     * 外部信号：收到取消或中断信号
     */
    CANCELLED("外部取消中断", true);

    private final String description;
    private final boolean degraded;

    AgentTerminationStatus(String description, boolean degraded) {
        this.description = description;
        this.degraded = degraded;
    }
}
