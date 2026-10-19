package com.financial.copilot.agent.core.business.agents.a2a.protocol;

/** A2A 长时任务生命周期状态枚举 */
public enum A2aTaskStatus {
    SUBMITTED,
    WORKING,
    COMPLETED,
    FAILED,
    CANCELLED
}
