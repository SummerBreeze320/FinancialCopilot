package com.financial.copilot.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 基金经理领域实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundManager implements Serializable {

    private String managerId;
    private String managerName;
    private String companyId;
    private String gender;
    private String education;
    private Integer workingDays;
    private BigDecimal currentTotalScaleBillion;
    private String bestFundCode;
    private BigDecimal bestFundReturn;
}
