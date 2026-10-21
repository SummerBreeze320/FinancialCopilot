package com.financial.copilot.agent.core.infra.coordination;

/**
 * <h1>智能体操作状态枚举</h1>
 * <p>
 * 严格限定状态迁移为：
 * REQUESTED -> RUNNING -> SUCCEEDED / FAILED / CANCELLED
 * 终态不可逆转。
 * </p>
 */
public enum OperationStatus {
    /**
     * 已登记请求，等待获取执行权
     */
    REQUESTED,

    /**
     * 正在执行确定性工具或子 Agent 动作
     */
    RUNNING,

    /**
     * 成功完成并已沉淀结果引用
     */
    SUCCEEDED,

    /**
     * 执行失败并已记录异常原因
     */
    FAILED,

    /**
     * 已被协调器或用户取消
     */
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
