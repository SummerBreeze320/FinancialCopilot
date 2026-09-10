package com.financial.copilot.domain.billing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * <h1>创建充值订单请求传输对象 (Recharge Order Create DTO)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RechargeOrderCreateDTO implements Serializable {

    /**
     * 充值套餐 ID
     */
    private Long packageId;

    /**
     * 支付通道: WECHAT(微信支付), ALIPAY(支付宝), BANK(机构转账)
     */
    private String payChannel;
}
