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
 * <h1>用户自选股持久化实体 (StockWatchlistPO)</h1>
 * <p>对应数据库表: {@code user_stock_watchlist}</p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_stock_watchlist")
public class StockWatchlistPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String stockCode;

    private String stockName;

    private String groupName;

    private BigDecimal targetPrice;

    private String notes;

    private String priority;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
