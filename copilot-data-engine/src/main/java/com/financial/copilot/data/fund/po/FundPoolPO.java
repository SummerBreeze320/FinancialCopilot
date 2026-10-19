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
 * <h1>用户自选基金池持久化实体 (FundPoolPO)</h1>
 * <p>对应数据库表: {@code user_fund_pool}</p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_fund_pool")
public class FundPoolPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String fundCode;

    private String fundName;

    private String poolType;

    private BigDecimal targetWeight;

    private String reason;

    private String tags;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
