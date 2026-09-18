package com.financial.copilot.data.billing.po;

import com.baomidou.mybatisplus.annotation.*;
import com.financial.copilot.domain.billing.entity.UserWallet;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * <h1>用户积分钱包持久化对象 (MyBatis-Plus PO)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user_wallet")
public class UserWalletPO {

    /**
     * 自增主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 系统用户 ID（唯一索引）
     */
    private Long userId;

    /**
     * 租户隔离标识
     */
    private String tenantId;

    /**
     * 当前可用算力积分余额
     */
    private Long balancePoints;

    /**
     * 冻结中的算力积分数额（如运行中会话预扣押）
     */
    private Long frozenPoints;

    /**
     * 账户历史累计充值获得的积分总量
     */
    private Long totalRechargedPoints;

    /**
     * 账户历史累计消耗的积分总量
     */
    private Long totalConsumedPoints;

    /**
     * 钱包状态：ACTIVE(正常可用), FROZEN(已被风控冻结)
     */
    private String walletStatus;

    /**
     * 乐观锁版本号 (CAS 防超扣)
     */
    @Version
    private Long version;

    /**
     * 钱包最后更新时间戳
     */
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
