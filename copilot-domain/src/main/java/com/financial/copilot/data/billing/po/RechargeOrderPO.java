package com.financial.copilot.data.billing.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financial.copilot.domain.billing.entity.RechargeOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <h1>充值交易订单持久化对象 (MyBatis-Plus PO)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_recharge_order")
public class RechargeOrderPO {

    /**
     * 自增主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 系统业务充值订单号（唯一防重流水号）
     */
    private String orderNo;

    /**
     * 充值用户 ID
     */
    private Long userId;

    /**
     * 购买的充值套餐 ID
     */
    private Long packageId;

    /**
     * 实际支付人民币金额（单位：元）
     */
    private BigDecimal payAmountCny;

    /**
     * 充值到账目标总算力积分（含赠送积分）
     */
    private Long targetPoints;

    /**
     * 支付渠道（ALIPAY 支付宝 / WECHAT 微信支付 / MANUAL 人工入账等）
     */
    private String payChannel;

    /**
     * 订单状态：CREATED(待支付), PAID(已支付入账), CANCELLED(已取消), EXPIRED(超时失效)
     */
    private String orderStatus;

    /**
     * 第三方支付流水号
     */
    private String thirdPartyTradeNo;

    /**
     * 订单生成创建时间戳
     */
    private LocalDateTime createdAt;

    /**
     * 实际到账支付时间戳
     */
    private LocalDateTime paidAt;

    public RechargeOrder toDomain() {
        return RechargeOrder.builder()
                .id(id)
                .orderNo(orderNo)
                .userId(userId)
                .packageId(packageId)
                .payAmountCny(payAmountCny)
                .targetPoints(targetPoints)
                .payChannel(payChannel)
                .orderStatus(orderStatus)
                .thirdPartyTradeNo(thirdPartyTradeNo)
                .createdAt(createdAt)
                .paidAt(paidAt)
                .build();
    }

    public static RechargeOrderPO fromDomain(RechargeOrder domain) {
        if (domain == null) return null;
        return RechargeOrderPO.builder()
                .id(domain.getId())
                .orderNo(domain.getOrderNo())
                .userId(domain.getUserId())
                .packageId(domain.getPackageId())
                .payAmountCny(domain.getPayAmountCny())
                .targetPoints(domain.getTargetPoints())
                .payChannel(domain.getPayChannel())
                .orderStatus(domain.getOrderStatus())
                .thirdPartyTradeNo(domain.getThirdPartyTradeNo())
                .createdAt(domain.getCreatedAt())
                .paidAt(domain.getPaidAt())
                .build();
    }
}
