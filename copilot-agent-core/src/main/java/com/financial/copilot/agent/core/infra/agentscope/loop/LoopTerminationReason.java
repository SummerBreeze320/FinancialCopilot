package com.financial.copilot.agent.core.infra.agentscope.loop;

/**
 * <h1>Agent 循环终止原因常量类 (LoopTerminationReason)</h1>
 * <p>提供与 {@link AgentTerminationStatus} 严格对应的常量字符定义，保持 API 与单测兼容。</p>
 *
 * @author FinancialCopilot
 */
public final class LoopTerminationReason {

    /**
     * a) 任务正常完成判定
     */
    public static final String TASK_COMPLETED = AgentTerminationStatus.TASK_COMPLETED.name();

    /**
     * b) 无可用下一步动作
     */
    public static final String NO_AVAILABLE_ACTION = AgentTerminationStatus.NO_AVAILABLE_ACTION.name();

    /**
     * c) 达到最大允许迭代轮数 (硬上限兜底)
     */
    public static final String MAX_ITERATIONS_REACHED = AgentTerminationStatus.MAX_ITERATIONS_REACHED.name();

    /**
     * d1) 不可恢复错误：工具连续失败达到最大容忍上限触发熔断阻断
     */
    public static final String TOOL_CIRCUIT_BROKEN = AgentTerminationStatus.TOOL_CIRCUIT_BROKEN.name();

    /**
     * d2) 不可恢复错误：权限拒绝或越权访问
     */
    public static final String PERMISSION_DENIED = AgentTerminationStatus.PERMISSION_DENIED.name();

    /**
     * 死循环防护：连续完全相同入参重复调用相同工具
     */
    public static final String CONSECUTIVE_DUPLICATE_CALL = AgentTerminationStatus.CONSECUTIVE_DUPLICATE_CALL.name();

    /**
     * 死循环防护：工具间周期性振荡往复调用 (例如 A -> B -> A -> B 死锁)
     */
    public static final String PING_PONG_OSCILLATION = AgentTerminationStatus.PING_PONG_OSCILLATION.name();

    /**
     * 外部信号：收到取消或中断信号
     */
    public static final String CANCELLED = AgentTerminationStatus.CANCELLED.name();

    private LoopTerminationReason() {
        // 常量类私有构造
    }
}
