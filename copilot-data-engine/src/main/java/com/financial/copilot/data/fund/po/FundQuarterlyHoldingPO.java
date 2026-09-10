package com.financial.copilot.data.fund.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 基金季度前十大重仓明细持久化对象 (MyBatis-Plus)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("fund_quarterly_holdings")
public class FundQuarterlyHoldingPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String fundCode;

    private String reportQuarter;

    private Integer rankOrder;

    private String stockCode;

    private String stockName;

    private BigDecimal holdingRatio;

    private BigDecimal holdingSharesTenThousand;

    private String holdingSector;
}
