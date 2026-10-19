package com.financial.copilot.agent.core.infra.memory;

/**
 * 记忆隔离作用域：区分全局市场知识、用户专属记忆和单会话短期记忆。
 */
public enum MemoryScope {
    GLOBAL_MARKET,
    USER,
    CONVERSATION
}
