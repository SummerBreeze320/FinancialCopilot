package com.financial.copilot.domain.billing.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <h1>算力点数在线充值交易订单领域实体 (Recharge Order Entity)</h1>
 * <p>
 * 职责：记录客户发起的充值交易订单生命周期，包含待支付、已支付、已取消状态流转与第三方流水凭据。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RechargeOrder implements Serializable {

    /**
     * 订单主键 ID
     */
    private Long id;

    /**
     * 全局唯一业务交易订单号
     */
    private String orderNo;

    /**
     * 充值发起用户 ID
     */
    private Long userId;

    /**
     * 购买的套餐规格 ID
     */
    private Long packageId;

    /**
     * 实际支付人民币总额 (元)
     */
    private BigDecimal payAmountCny;

    /**
     * 支付成功后应发放到账的总算力点数 (含赠送)
     */
    private Long targetPoints;

    /**
     * 支付通道: WECHAT(微信支付), ALIPAY(支付宝), BANK(机构对公转账)
     */
    private String payChannel;

    /**
     * 订单交易状态: PENDING(待付款), PAID(已到账), CANCELLED(已超时取消)
     */
    @Builder.Default
    private String orderStatus = "PENDING";

    /**
     * 第三方支付网关交易凭证流水号
     */
    private String thirdPartyTradeNo;

    /**
     * 订单创建发起时间
     */
    private LocalDateTime createdAt;

    /**
     * 支付成功到账确认时间
     */
    private LocalDateTime paidAt;
}
