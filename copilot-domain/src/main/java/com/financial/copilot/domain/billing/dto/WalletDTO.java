package com.financial.copilot.domain.billing.dto;

import com.financial.copilot.domain.billing.entity.UserWallet;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * <h1>用户算力钱包对外展示传输对象 (Wallet DTO)</h1>
 * <p>
 * 包含点数余额、折合法币估值、累计充值与消耗、以及约可生成的报告份数估算。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletDTO implements Serializable {

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 当前可用算力点数
     */
    private Long balancePoints;

    /**
     * 折合法币预估金额 (元，基于 10,000点 = 1元)
     */
    private BigDecimal estimatedCny;

    /**
     * 累计充值点数总额
     */
    private Long totalRechargedPoints;

    /**
     * 累计消耗点数总额
     */
    private Long totalConsumedPoints;

    /**
     * 钱包状态 (NORMAL / ARREARS / FROZEN)
     */
    private String walletStatus;

    /**
     * 预估尚可生成标准深度投研报告的份数 (按平均 2,000 点/份预估)
     */
    private Long estimatedReportsRemaining;

    /**
     * 从持久化实体构建展示 DTO
     *
     * @param wallet 钱包领域实体
     * @return 展示传输对象
     */
    public static WalletDTO fromEntity(UserWallet wallet) {
        if (wallet == null) {
            return WalletDTO.builder()
                    .balancePoints(0L)
                    .estimatedCny(BigDecimal.ZERO)
                    .totalRechargedPoints(0L)
                    .totalConsumedPoints(0L)
                    .walletStatus("NORMAL")
                    .estimatedReportsRemaining(0L)
                    .build();
        }

        long balance = wallet.getBalancePoints() != null ? wallet.getBalancePoints() : 0L;
        BigDecimal cny = BigDecimal.valueOf(balance).divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP);
        long reports = balance / 2000L;

        return WalletDTO.builder()
                .userId(wallet.getUserId())
                .balancePoints(balance)
                .estimatedCny(cny)
                .totalRechargedPoints(wallet.getTotalRechargedPoints() != null ? wallet.getTotalRechargedPoints() : 0L)
                .totalConsumedPoints(wallet.getTotalConsumedPoints() != null ? wallet.getTotalConsumedPoints() : 0L)
                .walletStatus(wallet.getWalletStatus())
                .estimatedReportsRemaining(reports)
                .build();
    }
}
