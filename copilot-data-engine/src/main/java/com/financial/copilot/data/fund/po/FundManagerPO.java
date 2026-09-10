package com.financial.copilot.data.fund.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 基金经理信息持久化对象 (MyBatis-Plus)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("fund_manager")
public class FundManagerPO {

    @TableId(value = "manager_id", type = IdType.INPUT)
    private String managerId;

    private String managerName;

    private String companyId;

    private String gender;

    private String education;

    private Integer workingDays;

    private BigDecimal currentTotalScaleBillion;

    private String bestFundCode;

    private BigDecimal bestFundReturn;

    private LocalDateTime createdAt;
}
