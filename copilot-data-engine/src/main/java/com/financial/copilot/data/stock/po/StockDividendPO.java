package com.financial.copilot.data.stock.po;

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
 * <h1>股票分红送转历史持久化对象 (Stock Dividend PO)</h1>
 * <p>
 * 对应数据库物理表: {@code stock_dividend_history}
 * 记录上市公司历年现金分红、送股与转增股本明细，为股息率与分红率分析提供数据支撑。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stock_dividend_history")
public class StockDividendPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 股票代码 (如 600519.SH) */
    private String stockCode;

    /** 公告日 */
    private LocalDate announcementDate;

    /** 股权登记日 */
    private LocalDate recordDate;

    /** 除权除息日 */
    private LocalDate exDividendDate;

    /** 分红年度 (如 2023) */
    private String dividendYear;

    /** 每10股派现金额 (元, 含税) */
    private BigDecimal cashDividendPer10;

    /** 每10股送股数 (股) */
    private BigDecimal bonusSharesPer10;

    /** 每10股转增数 (股) */
    private BigDecimal transferSharesPer10;

    /** 股息率 (%) */
    private BigDecimal dividendRatio;

    /** 股利支付率 (%) */
    private BigDecimal payoutRatio;

    private LocalDateTime createdAt;
}
