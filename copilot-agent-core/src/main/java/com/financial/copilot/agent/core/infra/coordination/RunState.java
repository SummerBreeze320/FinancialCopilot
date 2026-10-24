package com.financial.copilot.agent.core.infra.coordination;

/**
 * <h1>工作流运行生命周期状态</h1>
 */
public enum RunState {
    /**
     * 排队中，等待获取会话排他锁或用户容量许可
     */
    QUEUED,

    /**
     * 正在执行 Supervisor 或 Subagents 编排
     */
    RUNNING,

    /**
     * 成功完成并已合成最终答复
     */
    SUCCEEDED,

    /**
     * 执行失败
     */
    FAILED,

    /**
     * 已被取消
     */
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
