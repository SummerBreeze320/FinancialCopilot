package com.financial.copilot.domain.graph.entity;

import java.math.BigDecimal;

/**
 * <h1>基金持仓股票关系实体</h1>
 */
public record HoldingRelation(
    String fundCode,
    String stockCode,
    String stockName,
    BigDecimal ratio,
    String quarter
) {}
