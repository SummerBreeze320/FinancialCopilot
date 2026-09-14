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
 * <h1>基金季度重仓持股明细持久化实体 (Fund Quarterly Holding PO)</h1>
 * <p>
 * 对应数据库物理表: {@code fund_quarterly_holdings}
 * 记录公募基金季度披露的前十大重仓股票、持仓占比、持股数量及所属行业。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("fund_quarterly_holdings")
public class FundQuarterlyHoldingPO {

    /**
     * 自增主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 基金代码（如 "005827.OF"）
     */
    private String fundCode;

    /**
     * 定期报告季度（例如 "2024Q3", "2024Q4"）
     */
    private String reportQuarter;

    /**
     * 重仓排序位次（1 ~ 10）
     */
    private Integer rankOrder;

    /**
     * 重仓个股代码（例如 "600519.SH", "000858.SZ"）
     */
    private String stockCode;

    /**
     * 重仓个股股票名称（例如 "贵州茅台", "五粮液"）
     */
    private String stockName;

    /**
     * 占基金净资产比例 (%)
     */
    private BigDecimal holdingRatio;

    /**
     * 持股数量（万股）
     */
    private BigDecimal holdingSharesTenThousand;

    /**
     * 所属行业板块名称（申万行业或证监会行业）
     */
    private String holdingSector;
}
