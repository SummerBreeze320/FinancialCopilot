package com.financial.copilot.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <h1>基金量化指标数据传输对象 (兼容性门面)</h1>
 * <p>
 * 推荐迁移使用专属领域包: {@link com.financial.copilot.common.fund.dto.FundMetricsDTO}
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundMetricsDTO implements Serializable {

    private String fundCode;
    private String fundName;
    private String fundType;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal cumulativeReturn;
    private BigDecimal annualizedReturn;
    private BigDecimal maxDrawdown;
    private BigDecimal annualizedVolatility;
    private BigDecimal sharpeRatio;
    private BigDecimal calmarRatio;
    private BigDecimal top10Concentration;
    private String primarySector;
    private BigDecimal primarySectorRatio;

    /**
     * 转换为领域包标准 DTO
     */
    public com.financial.copilot.common.fund.dto.FundMetricsDTO toDomainDTO() {
        return com.financial.copilot.common.fund.dto.FundMetricsDTO.builder()
                .fundCode(fundCode)
                .fundName(fundName)
                .fundType(fundType)
                .startDate(startDate)
                .endDate(endDate)
                .cumulativeReturn(cumulativeReturn)
                .annualizedReturn(annualizedReturn)
                .maxDrawdown(maxDrawdown)
                .annualizedVolatility(annualizedVolatility)
                .sharpeRatio(sharpeRatio)
                .calmarRatio(calmarRatio)
                .top10Concentration(top10Concentration)
                .primarySector(primarySector)
                .primarySectorRatio(primarySectorRatio)
                .build();
    }
}
