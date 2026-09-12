package com.financial.copilot.agent.core.dag.model;

/**
 * <h1>节点级失败策略枚举</h1>
 */
public enum FailurePolicy {
    /** 强硬失败：当前节点失败则整个图或下游关键依赖链立即终止报错 (如核心标的初筛) */
    FAIL_FAST,
    /** 忽略失败继续流转：下游放行，产物标记 evidenceIncomplete=true，报告中注明 (如宏观新闻舆情) */
    CONTINUE,
    /** 指数退避重试：在当前节点内部重试 N 次 (默认配合 maxRetries=2) */
    RETRY,
    /** 降级保底数据：失败时注入预置 Fallback 数据，下游无缝继续 (如历史行情走备用源) */
    FALLBACK,
    /** 可选分支：若失败则整条支路静默标记为 SKIPPED，不阻断汇聚节点 (如可选估值模型) */
    OPTIONAL
}
