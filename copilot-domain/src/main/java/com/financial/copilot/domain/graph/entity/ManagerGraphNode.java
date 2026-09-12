package com.financial.copilot.domain.graph.entity;

/**
 * <h1>基金经理知识图谱节点</h1>
 */
public record ManagerGraphNode(
    String id,
    String name,
    String gender,
    String education,
    Integer workYears
) {}
