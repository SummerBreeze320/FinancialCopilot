package com.financial.copilot.agent.core.infra.supervisor;

/**
 * <h1>Supervisor 总体运行状态枚举</h1>
 * 严格定义投研生命周期在 PLANNING, EXECUTING, VALIDATING, REVIEWING, AGGREGATING 与终态之间的单向/有界流转。
 *
 * @author FinancialCopilot
 */
public enum SupervisorState {
    /** 任务规划阶段，由 Planner 生成并校验 TaskDAG */
    PLANNING,
    /** 节点执行阶段，由 Scheduler 调度专员 Subagent 并行/串行执行 */
    EXECUTING,
    /** 确定性校验阶段，由 Java 校验器验证产物类型、Schema、大小与证据边界 */
    VALIDATING,
    /** 跨节点质量审查阶段，由 ReviewerAgent 核验一致性并输出裁决 */
    REVIEWING,
    /** 研报聚合阶段，由 AggregatorAgent 基于已通过产物合成最终输出 */
    AGGREGATING,
    /** 正常成功终态 */
    SUCCEEDED,
    /** 严重不可恢复失败终态 */
    FAILED,
    /** 降级完成终态（存在缺口或重试超限，已在研报中显式披露） */
    DEGRADED
}
