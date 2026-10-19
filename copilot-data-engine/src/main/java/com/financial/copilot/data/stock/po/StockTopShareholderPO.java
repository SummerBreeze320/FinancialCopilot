package com.financial.copilot.data.stock.po;

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
 * <h1>股票十大股东持久化对象 (Stock Top Shareholder PO)</h1>
 * <p>
 * 对应数据库物理表: {@code stock_top_shareholders}
 * 记录上市公司每季度前十大股东的持股明细，用于分析筹码集中度与机构动向。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stock_top_shareholders")
public class StockTopShareholderPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 股票代码 (如 600519.SH) */
    private String stockCode;

    /** 报告季度 (如 2024Q2) */
    private String reportQuarter;

    /** 股东排名 (1-10) */
    private Integer rankOrder;

    /** 股东名称 */
    private String shareholderName;

    /** 股东类型 (机构/个人/基金/QFII/社保/保险) */
    private String shareholderType;

    /** 持股数量 (万股) */
    private BigDecimal holdingSharesTenThousand;

    /** 持股比例 (%) */
    private BigDecimal holdingRatio;

    /** 变动情况 (增持/减持/新进/不变/退出) */
    private String holdingChange;

    /** 持股市值 (亿元) */
    private BigDecimal holdingMarketValueBillion;

    private LocalDateTime createdAt;
}
