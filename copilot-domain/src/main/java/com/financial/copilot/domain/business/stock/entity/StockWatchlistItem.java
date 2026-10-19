package com.financial.copilot.domain.business.stock.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * <h1>自选股票领域实体 (StockWatchlistItem)</h1>
 * <p>封装用户私有自选股票标的、分组归属、目标建仓/止盈止损价位与追踪逻辑。</p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockWatchlistItem {
    /** 自增主键 */
    private Long id;

    /** 所属用户标识 (强隔离) */
    private Long userId;

    /** 6位股票代码 (如: 600519) */
    private String stockCode;

    /** 股票简称 */
    private String stockName;

    /** 自选分组名称 (如: "默认", "半导体芯片", "高股息红利") */
    private String groupName;

    /** 目标建仓价或心理关注价位 */
    private BigDecimal targetPrice;

    /** 关注理由与催化剂说明 */
    private String notes;

    /** 关注度等级: HIGH, MEDIUM, LOW */
    private String priority;

    /** 加入时间 */
    private Instant createdAt;

    /** 更新时间 */
    private Instant updatedAt;
}
