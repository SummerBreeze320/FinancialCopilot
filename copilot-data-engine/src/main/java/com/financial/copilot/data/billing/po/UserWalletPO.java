package com.financial.copilot.data.billing.po;

import com.baomidou.mybatisplus.annotation.*;
import com.financial.copilot.domain.billing.entity.UserWallet;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * <h1>用户算力钱包持久化对象 (MyBatis-Plus PO)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user_wallet")
public class UserWalletPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String tenantId;

    private Long balancePoints;

    private Long frozenPoints;

    private Long totalRechargedPoints;

    private Long totalConsumedPoints;

    private String walletStatus;

    @Version
    private Long version;

    private LocalDateTime updatedAt;

    public UserWallet toDomain() {
        return UserWallet.builder()
                .id(id)
                .userId(userId)
                .tenantId(tenantId)
                .balancePoints(balancePoints)
                .frozenPoints(frozenPoints)
                .totalRechargedPoints(totalRechargedPoints)
                .totalConsumedPoints(totalConsumedPoints)
                .walletStatus(walletStatus)
                .version(version)
                .updatedAt(updatedAt)
                .build();
    }

    public static UserWalletPO fromDomain(UserWallet domain) {
        if (domain == null) return null;
        return UserWalletPO.builder()
                .id(domain.getId())
                .userId(domain.getUserId())
                .tenantId(domain.getTenantId())
                .balancePoints(domain.getBalancePoints())
                .frozenPoints(domain.getFrozenPoints())
                .totalRechargedPoints(domain.getTotalRechargedPoints())
                .totalConsumedPoints(domain.getTotalConsumedPoints())
                .walletStatus(domain.getWalletStatus())
                .version(domain.getVersion())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
