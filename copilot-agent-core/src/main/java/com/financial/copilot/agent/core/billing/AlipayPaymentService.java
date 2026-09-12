package com.financial.copilot.agent.core.billing;

import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.internal.util.AlipaySignature;
import com.financial.copilot.domain.billing.port.BillingPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AlipayPaymentService {
    private final AlipayProperties properties;
    private final BillingPort billingPort;
    private final WalletBillingService billingService;

    private void requireEnabled() {
        if (!properties.isEnabled() || blank(properties.getAppId()) || blank(properties.getSellerId())
                || blank(properties.getPublicKey())) {
            throw new IllegalStateException("支付宝支付未配置");
        }
    }

    public String paymentUrl(Long userId, String orderNo) {
        requireEnabled();
        if (blank(properties.getPrivateKey()) || blank(properties.getNotifyUrl())
                || !properties.getNotifyUrl().startsWith("https://")) {
            throw new IllegalStateException("支付宝私钥或 HTTPS 通知地址未配置");
        }
        var order = billingPort.getOrderByNo(orderNo).orElseThrow(() -> new IllegalArgumentException("Order not found"));
        if (!order.getUserId().equals(userId)) throw new SecurityException("Order owner mismatch");
        if (!"PENDING".equals(order.getOrderStatus()) || !"ALIPAY".equals(order.getPayChannel())) {
            throw new IllegalArgumentException("Order is not payable");
        }
        var model = new AlipayTradePagePayModel();
        model.setOutTradeNo(orderNo);
        model.setTotalAmount(order.getPayAmountCny().toPlainString());
        model.setSubject("FinancialCopilot 算力充值");
        model.setProductCode("FAST_INSTANT_TRADE_PAY");
        var request = new AlipayTradePagePayRequest();
        request.setBizModel(model);
        request.setNotifyUrl(properties.getNotifyUrl());
        String gateway = properties.isSandbox() ? "https://openapi-sandbox.dl.alipaydev.com/gateway.do"
                : "https://openapi.alipay.com/gateway.do";
        try {
            return new DefaultAlipayClient(gateway, properties.getAppId(), properties.getPrivateKey(),
                    "json", "UTF-8", properties.getPublicKey(), "RSA2").pageExecute(request, "GET").getBody();
        } catch (com.alipay.api.AlipayApiException e) {
            throw new IllegalStateException("Unable to create Alipay payment URL", e);
        }
    }

    public void acceptNotification(Map<String, String> parameters) {
        requireEnabled();
        if (!"RSA2".equals(parameters.get("sign_type"))) throw new SecurityException("Invalid sign type");
        try {
            if (!AlipaySignature.rsaCheckV1(new HashMap<>(parameters), properties.getPublicKey(), "UTF-8", "RSA2")) {
                throw new SecurityException("Invalid Alipay signature");
            }
        } catch (com.alipay.api.AlipayApiException e) {
            throw new SecurityException("Invalid Alipay signature", e);
        }
        if (!properties.getAppId().equals(parameters.get("app_id"))
                || !properties.getSellerId().equals(parameters.get("seller_id"))) {
            throw new SecurityException("Payment merchant mismatch");
        }
        String status = parameters.get("trade_status");
        if (!"TRADE_SUCCESS".equals(status) && !"TRADE_FINISHED".equals(status)) {
            if ("WAIT_BUYER_PAY".equals(status) || "TRADE_CLOSED".equals(status)) return;
            throw new IllegalArgumentException("Invalid trade status");
        }
        String amount = parameters.get("total_amount");
        if (amount == null || !amount.matches("[0-9]{1,12}(\\.[0-9]{1,2})?")) {
            throw new IllegalArgumentException("Invalid payment amount");
        }
        billingService.payCallback(parameters.get("out_trade_no"), parameters.get("trade_no"), new BigDecimal(amount));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
