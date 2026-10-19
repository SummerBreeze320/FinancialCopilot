package com.financial.copilot.agent.core.infra.dag.scheduler;

/**
 * <h1>DAG 运行准入裁决结果 (Admission Decision)</h1>
 * <p>
 * 定义图运行请求在提交后的准入状态，支持即时准入、排队等待以及在系统或用户超额时的拒绝保护。
 * </p>
 */
public enum AdmissionDecision {

    /** 准入成功，立即开始图执行 */
    ADMITTED,

    /** 因超出单用户并发运行上限 (max-active-runs-per-user) 而进入排队 */
    QUEUED_USER_LIMIT,

    /** 因同会话串行化约束 (单会话只允许一个活跃运行) 而进入排队 */
    QUEUED_CONVERSATION,

    /** 超过单用户排队上限或配额限制被拒绝 */
    REJECTED_USER_LIMIT,

    /** 超过系统全局排队容量 (max-queued-runs) 被拒绝 (服务过载) */
    REJECTED_SYSTEM_CAPACITY;

    /**
     * 判断当前裁决是否为准入执行
     *
     * @return 若已准入返回 true
     */
    public boolean isAdmitted() {
        return this == ADMITTED;
    }

    /**
     * 判断当前裁决是否为排队等待
     *
     * @return 若已入队等待返回 true
     */
    public boolean isQueued() {
        return this == QUEUED_USER_LIMIT || this == QUEUED_CONVERSATION;
    }

    /**
     * 判断当前裁决是否为拒绝受理
     *
     * @return 若被拒绝返回 true
     */
    public boolean isRejected() {
        return this == REJECTED_USER_LIMIT || this == REJECTED_SYSTEM_CAPACITY;
    }
}
