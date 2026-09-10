package com.financial.copilot.data.fund.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 基金管理公司持久化对象 (MyBatis-Plus)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("fund_company")
public class FundCompanyPO {

    @TableId(value = "company_id", type = IdType.INPUT)
    private String companyId;

    private String companyName;

    private String shortName;

    private LocalDate establishmentDate;

    private BigDecimal totalScaleBillion;

    private BigDecimal equityScaleBillion;

    private Integer managerCount;

    private Integer fundCount;

    private LocalDateTime createdAt;
}
