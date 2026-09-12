package com.financial.copilot.agent.core.dag.runtime.resource;

/**
 * <h1>DAG 节点执行优先级枚举</h1>
 */
public enum NodePriority {
    /** 关键路径主干任务 */
    HIGH(10),
    /** 常规支撑任务 */
    NORMAL(5),
    /** 旁路或异步可选任务 */
    LOW(1);

    private final int weight;

    NodePriority(int weight) {
        this.weight = weight;
    }

    public int getWeight() {
        return weight;
    }
}
