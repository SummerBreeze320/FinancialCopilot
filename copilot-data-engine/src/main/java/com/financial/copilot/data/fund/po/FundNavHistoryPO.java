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

/**
 * 基金历史净值时序持久化对象 (MyBatis-Plus)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("fund_nav_history")
public class FundNavHistoryPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String fundCode;

    private LocalDate navDate;

    private BigDecimal unitNav;

    private BigDecimal accumulatedNav;

    private BigDecimal adjustedNav;

    private BigDecimal dailyGrowthRate;
}
