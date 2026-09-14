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
 * <h1>基金历史净值时序持久化实体 (Fund NAV History PO)</h1>
 * <p>
 * 对应数据库物理表: {@code fund_nav_history}
 * 记录基金每日单位净值、累计净值、复权单位净值及日涨跌幅时序数据，供量化指标计算引擎使用。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("fund_nav_history")
public class FundNavHistoryPO {

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
     * 净值公布日期
     */
    private LocalDate navDate;

    /**
     * 单位净值 (Unit NAV)
     */
    private BigDecimal unitNav;

    /**
     * 累计单位净值 (Accumulated NAV)
     */
    private BigDecimal accumulatedNav;

    /**
     * 复权单位净值 (Adjusted NAV，计入分红与拆分再投资，用于真实收益率测算)
     */
    private BigDecimal adjustedNav;

    /**
     * 日收益增长率 (%)
     */
    private BigDecimal dailyGrowthRate;
}
