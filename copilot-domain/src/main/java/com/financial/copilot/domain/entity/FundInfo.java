package com.financial.copilot.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 基金标的领域实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundInfo implements Serializable {

    private String fundCode;
    private String fundName;
    private String fundType;
    private LocalDate establishmentDate;
    private String managementCompanyId;
    private BigDecimal currentScaleBillion;
    private String trackingBenchmark;
    private String custodianBank;
}
