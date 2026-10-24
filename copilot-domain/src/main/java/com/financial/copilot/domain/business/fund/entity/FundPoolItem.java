package com.financial.copilot.domain.business.fund.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * <h1>基金池标的领域实体 (FundPoolItem)</h1>
 * <p>封装用户私有基金池标的、分类池属性、目标建议配置比例与投资逻辑。</p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundPoolItem {
    /** 自增主键 */
    private Long id;

    /** 所属用户标识 (强隔离) */
    private Long userId;

    /** 6位基金代码 (如: 005827) */
    private String fundCode;

    /** 基金简称 */
    private String fundName;

    /** 池分类类型: CORE(核心池), WATCH(观察池), CANDIDATE(备选池) */
    private String poolType;

    /** 目标建议仓位配置比例 (0.0000 ~ 1.0000) */
    private BigDecimal targetWeight;

    /** 入选理由与投研逻辑总结 */
    private String reason;

    /** 标签列表 (逗号分隔，如 "蓝筹,消费,高夏普") */
    private String tags;

    /** 加入时间 */
    private Instant createdAt;

    /** 更新时间 */
    private Instant updatedAt;
}
