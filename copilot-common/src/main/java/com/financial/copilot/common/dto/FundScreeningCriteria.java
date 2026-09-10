package com.financial.copilot.common.dto;

import java.io.Serializable;

/**
 * <h1>公募基金筛选条件传输对象 (兼容性门面)</h1>
 * <p>
 * 推荐迁移使用专属领域包: {@link com.financial.copilot.common.fund.dto.FundScreeningCriteria}
 * </p>
 *
 * @author FinancialCopilot
 */
public record FundScreeningCriteria(
        String fundType,
        String sectorTheme,
        Double minScaleInBillion,
        Double maxScaleInBillion,
        Double maxDrawdown3YLimit,
        Double minSharpe3Y,
        Double minReturn3Y,
        Integer minManagerTenureYears,
        String sortBy,
        String sortOrder,
        Integer limit
) implements Serializable {

    public FundScreeningCriteria {
        if (limit == null || limit <= 0) {
            limit = 10;
        }
        if (sortOrder == null || sortOrder.isBlank()) {
            sortOrder = "DESC";
        }
    }

    /**
     * 转换为领域包强类型对象
     */
    public com.financial.copilot.common.fund.dto.FundScreeningCriteria toDomainDTO() {
        return new com.financial.copilot.common.fund.dto.FundScreeningCriteria(
                fundType, sectorTheme, minScaleInBillion, maxScaleInBillion,
                maxDrawdown3YLimit, minSharpe3Y, minReturn3Y, minManagerTenureYears,
                sortBy, sortOrder, limit
        );
    }
}
