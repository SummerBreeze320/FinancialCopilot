package com.financial.copilot.data.billing.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financial.copilot.domain.platform.billing.entity.RechargePackage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * <h1>充值套餐规格持久化对象 (MyBatis-Plus PO)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_recharge_package")
public class RechargePackagePO {

    /**
     * 自增主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 套餐显示名称（例如 "体验包", "进阶研报包", "专业机构包"）
     */
    private String packageName;

    /**
     * 套餐销售定价（单位：元）
     */
    private BigDecimal priceCny;

    /**
     * 购买获得的基础算力积分额度
     */
    private Long grantedPoints;

    /**
     * 限时赠送/促销奖励的算力积分额度
     */
    private Long bonusPoints;

    /**
     * UI 促销角标文本（如 "限时特惠", "最受欢迎", "性价比之王"）
     */
    private String badge;

    /**
     * 前台界面展示排序权重（升序）
     */
    private Integer sortOrder;

    /**
     * 套餐是否在架销售
     */
    private Boolean isActive;

    public RechargePackage toDomain() {
        return RechargePackage.builder()
                .id(id)
                .packageName(packageName)
                .priceCny(priceCny)
                .grantedPoints(grantedPoints)
                .bonusPoints(bonusPoints)
                .badge(badge)
                .sortOrder(sortOrder)
                .isActive(isActive)
                .build();
    }

    public static RechargePackagePO fromDomain(RechargePackage domain) {
        if (domain == null) return null;
        return RechargePackagePO.builder()
                .id(domain.getId())
                .packageName(domain.getPackageName())
                .priceCny(domain.getPriceCny())
                .grantedPoints(domain.getGrantedPoints())
                .bonusPoints(domain.getBonusPoints())
                .badge(domain.getBadge())
                .sortOrder(domain.getSortOrder())
                .isActive(domain.getIsActive())
                .build();
    }
}
