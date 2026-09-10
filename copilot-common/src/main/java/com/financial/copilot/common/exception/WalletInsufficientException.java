package com.financial.copilot.common.exception;

import lombok.Getter;

/**
 * <h1>算力钱包余额不足异常 (Wallet Insufficient Exception)</h1>
 * <p>
 * 触发 HTTP 402 状态码，前端捕获后自动弹出在线充值收银台抽屉。
 * </p>
 *
 * @author FinancialCopilot
 */
@Getter
public class WalletInsufficientException extends RuntimeException {

    private final Long userId;
    private final Long currentBalance;
    private final Long requiredPoints;

    public WalletInsufficientException(Long userId, Long currentBalance, Long requiredPoints) {
        super(String.format("用户 [%s] 智算点余额不足 (当前可用: %d 点, 启动门槛: %d 点)，请前往充值",
                userId, currentBalance != null ? currentBalance : 0, requiredPoints));
        this.userId = userId;
        this.currentBalance = currentBalance;
        this.requiredPoints = requiredPoints;
    }
}
