package com.financial.copilot.domain.graph.entity;

import java.math.BigDecimal;

/**
 * <h1>股票知识图谱节点</h1>
 */
public record StockGraphNode(
    String code,
    String name,
    String industry,
    BigDecimal marketCap
) {}
