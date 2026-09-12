package com.financial.copilot.domain.graph.entity;

import java.time.LocalDate;

/**
 * <h1>基金知识图谱节点</h1>
 */
public record FundGraphNode(
    String code,
    String name,
    String fundType,
    LocalDate establishmentDate
) {}
