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
 * 基金基础信息表持久化对象 (MyBatis-Plus)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("fund_info")
public class FundInfoPO {

    @TableId(value = "fund_code", type = IdType.INPUT)
    private String fundCode;

    private String fundName;

    private String fundType;

    private LocalDate establishmentDate;

    private String managementCompanyId;

    private BigDecimal currentScaleBillion;

    private String trackingBenchmark;

    private String custodianBank;

    private LocalDateTime createdAt;
}
