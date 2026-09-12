package com.financial.copilot.agent.core.billing;

import com.alipay.api.internal.util.AlipaySignature;
import com.financial.copilot.domain.billing.port.BillingPort;
import com.financial.copilot.domain.billing.entity.RechargeOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlipayPaymentServiceTest {
    private AlipayProperties properties;
    private WalletBillingService billing;
    private BillingPort port;
    private AlipayPaymentService service;
    private String privateKey;

    @BeforeEach
    void setUp() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        properties = new AlipayProperties();
        properties.setEnabled(true);
        properties.setAppId("test-app");
        properties.setSellerId("test-seller");
        properties.setPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        properties.setPrivateKey(privateKey);
        properties.setNotifyUrl("https://example.com/api/v1/billing/alipay/notify");
        billing = mock(WalletBillingService.class);
        port = mock(BillingPort.class);
        service = new AlipayPaymentService(properties, port, billing);
    }

    private Map<String, String> notification(String seller, String status) throws Exception {
        var params = new HashMap<String, String>();
        params.put("app_id", "test-app");
        params.put("seller_id", seller);
        params.put("out_trade_no", "ORD-test");
        params.put("trade_no", "ALIPAY-test");
        params.put("total_amount", "49.00");
        params.put("trade_status", status);
        params.put("sign", AlipaySignature.sign(params, privateKey, "UTF-8", "RSA2"));
        params.put("sign_type", "RSA2");
        return params;
    }

    @Test
    void validSignatureIsRequiredBeforeCreditingWallet() throws Exception {
        service.acceptNotification(notification("test-seller", "TRADE_SUCCESS"));
        verify(billing).payCallback("ORD-test", "ALIPAY-test", new BigDecimal("49.00"));
    }

    @Test
    void alteredAmountAndWrongMerchantCannotCreditWallet() throws Exception {
        var tampered = notification("test-seller", "TRADE_SUCCESS");
        tampered.put("total_amount", "1.00");
        assertThrows(SecurityException.class, () -> service.acceptNotification(tampered));
        var wrongSeller = notification("someone-else", "TRADE_SUCCESS");
        assertThrows(SecurityException.class, () -> service.acceptNotification(wrongSeller));
        verifyNoInteractions(billing);
    }

    @Test
    void unpaidAndUnconfiguredNotificationsNeverCredit() throws Exception {
        service.acceptNotification(notification("test-seller", "WAIT_BUYER_PAY"));
        properties.setEnabled(false);
        assertThrows(IllegalStateException.class, () -> service.acceptNotification(Map.of()));
        verifyNoInteractions(billing);
    }

    @Test
    void paymentUrlBelongsToCurrentUserAndUsesStoredAmount() {
        when(port.getOrderByNo("ORD-test")).thenReturn(Optional.of(RechargeOrder.builder()
                .orderNo("ORD-test").userId(7L).orderStatus("PENDING").payChannel("ALIPAY")
                .payAmountCny(new BigDecimal("49.00")).build()));
        assertThrows(SecurityException.class, () -> service.paymentUrl(8L, "ORD-test"));
        String url = service.paymentUrl(7L, "ORD-test");
        assertTrue(url.startsWith("https://openapi-sandbox.dl.alipaydev.com/gateway.do?"));
        assertTrue(url.contains("sign="));
        assertFalse(url.contains(privateKey));
    }
}
