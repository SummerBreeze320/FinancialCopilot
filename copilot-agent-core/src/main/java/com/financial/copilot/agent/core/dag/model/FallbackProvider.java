package com.financial.copilot.agent.core.dag.model;

/**
 * <h1>节点降级数据提供者函数式接口</h1>
 */
@FunctionalInterface
public interface FallbackProvider {
    /**
     * 当节点执行失败且策略为 FALLBACK 时生成保底产物或数据
     *
     * @param node 当前失败节点
     * @return 降级产物或载荷
     */
    Object provideFallback(GraphNode node);
}
