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

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long userId;

    private Long packageId;

    private BigDecimal payAmountCny;

    private Long targetPoints;

    private String payChannel;

    private String orderStatus;

    private String thirdPartyTradeNo;

    private LocalDateTime createdAt;

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
