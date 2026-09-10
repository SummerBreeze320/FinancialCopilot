package com.financial.copilot.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 基金管理公司领域实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundCompany implements Serializable {

    private String companyId;
    private String companyName;
    private String shortName;
    private LocalDate establishmentDate;
    private BigDecimal totalScaleBillion;   // 非货总管理规模 (亿元)
    private BigDecimal equityScaleBillion;  // 权益类管理规模 (亿元)
    private Integer managerCount;           // 旗下经理人数
    private Integer fundCount;              // 旗下基金数量
}
