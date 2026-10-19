package com.financial.copilot.agent.core.infra.supervisor;

/**
 * <h1>审查决策动作枚举</h1>
 * 标识 ReviewerAgent 对当前执行产物做出的闭环调度指示。
 *
 * @author FinancialCopilot
 */
public enum ReviewAction {
    /** 产物质量与证据链自洽且充分，放行进入聚合 */
    PASS,
    /** 目标任务存在局部缺陷或数据滞后，仅重新调度指定任务 */
    RETRY_TASK,
    /** 规划缺失或需要动态改图，调用 Planner 局部重规划剩余 DAG */
    REPLAN,
    /** 重试超限或关键外部数据不可得，保留已验证事实并降级聚合 */
    DEGRADE
}
