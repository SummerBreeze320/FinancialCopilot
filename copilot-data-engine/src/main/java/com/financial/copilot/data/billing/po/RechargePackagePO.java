package com.financial.copilot.data.billing.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financial.copilot.domain.billing.entity.RechargePackage;
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

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String packageName;

    private BigDecimal priceCny;

    private Long grantedPoints;

    private Long bonusPoints;

    private String badge;

    private Integer sortOrder;

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
