package com.financial.copilot.agent.core.dag.model;

/**
 * <h1>DAG 节点生命周期状态枚举</h1>
 */
public enum NodeStatus {
    /** 初始等待态：前驱依赖尚未全部就绪 */
    PENDING,
    /** 就绪态：前驱依赖全部满足，已进入调度队列 */
    READY,
    /** 运行态：Agent 正在虚拟线程中执行 */
    RUNNING,
    /** 成功态：执行完成，产物已归档至 ArtifactStore */
    SUCCEEDED,
    /** 失败态：发生异常且不可恢复 */
    FAILED,
    /** 级联跳过：前驱失败或由 Planner 动态剪枝 */
    SKIPPED,
    /** 取消态：外部主动中止会话或超时取消 */
    CANCELLED,
    /** 超时态：单节点执行超时 */
    TIMEOUT;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED || this == CANCELLED || this == TIMEOUT;
    }

    public boolean isSuccessOrSkipped() {
        return this == SUCCEEDED || this == SKIPPED;
    }
}
