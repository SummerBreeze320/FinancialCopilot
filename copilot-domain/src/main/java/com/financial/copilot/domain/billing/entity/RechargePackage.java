package com.financial.copilot.domain.billing.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * <h1>算力点数在线充值套餐规格领域实体 (Recharge Package Entity)</h1>
 * <p>
 * 职责：定义前端收银台展示的充值套餐（如尝鲜版 ¥49、进阶版 ¥199、专业版 ¥599、机构版 ¥2999），包含赠送点数与营销徽章。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RechargePackage implements Serializable {

    /**
     * 套餐主键 ID
     */
    private Long id;

    /**
     * 套餐展示名称 (如 "个人投研尝鲜包", "机构投研旗舰包")
     */
    private String packageName;

    /**
     * 标价人民币金额 (元)
     */
    private BigDecimal priceCny;

    /**
     * 基础到账算力点数
     */
    private Long grantedPoints;

    /**
     * 赠送福利算力点数 (如首充或限时加赠)
     */
    @Builder.Default
    private Long bonusPoints = 0L;

    /**
     * 营销角标文案 (如 "热销推荐", "限时加赠20%", "高性价比")
     */
    private String badge;

    /**
     * 前台排列展示顺序（正序）
     */
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * 套餐是否已上架供客户选购
     */
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 获取购买该套餐最终可获得的合计总算力点数
     *
     * @return 基础点数 + 赠送点数
     */
    public long getTotalPoints() {
        long base = grantedPoints != null ? grantedPoints : 0L;
        long bonus = bonusPoints != null ? bonusPoints : 0L;
        return base + bonus;
    }
}
