package com.financial.copilot.domain.platform.billing.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <h1>用户积分钱包领域实体 (User Wallet Entity)</h1>
 * <p>
 * 职责：维护客户在系统内的虚拟货币积分资产（1元人民币 = 10,000 积分）。
 * 具备悲观/乐观锁防并发超扣、冻结额度流控与充值累计统计。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserWallet implements Serializable {

    /**
     * 钱包主键 ID
     */
    private Long id;

    /**
     * 绑定的全局系统用户唯一 ID
     */
    private Long userId;

    /**
     * 多租户或机构唯一标识
     */
    private String tenantId;

    /**
     * 当前可用积分余额 (1 元 = 10,000 积分)
     */
    @Builder.Default
    private Long balancePoints = 0L;

    /**
     * 投研并发执行时临时锁定的冻结积分
     */
    @Builder.Default
    private Long frozenPoints = 0L;

    /**
     * 历史累计充值积分总额
     */
    @Builder.Default
    private Long totalRechargedPoints = 0L;

    /**
     * 历史累计消费消耗积分总额
     */
    @Builder.Default
    private Long totalConsumedPoints = 0L;

    /**
     * 钱包状态: NORMAL(正常可用), ARREARS(已欠费), FROZEN(已被安全风控冻结)
     */
    @Builder.Default
    private String walletStatus = "NORMAL";

    /**
     * 乐观锁版本号（高并发扣减防重与防超扣）
     */
    @Builder.Default
    private Long version = 0L;

    /**
     * 最后一次余额变动更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 判定钱包是否可用并满足最低调用积分
     *
     * @param minPoints 最低积分门槛
     * @return true 允许执行，false 拦截
     */
    public boolean hasSufficientBalance(long minPoints) {
        return "NORMAL".equalsIgnoreCase(walletStatus) && balancePoints != null && balancePoints >= minPoints;
    }
}
