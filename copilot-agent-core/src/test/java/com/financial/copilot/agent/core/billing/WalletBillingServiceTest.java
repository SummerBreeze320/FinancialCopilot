package com.financial.copilot.agent.core.billing;

import com.financial.copilot.common.exception.WalletInsufficientException;
import com.financial.copilot.domain.billing.dto.TokenDeductionResult;
import com.financial.copilot.domain.billing.dto.UsageTrendPointDTO;
import com.financial.copilot.domain.billing.dto.WalletDTO;
import com.financial.copilot.domain.billing.entity.*;
import com.financial.copilot.domain.billing.port.BillingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <h1>算力计量计费与钱包核心业务服务单元测试 (Wallet Billing Service Test)</h1>
 *
 * @author FinancialCopilot
 */
class WalletBillingServiceTest {
    @Test
    void failedDeductionDoesNotWriteSuccessfulLedger() {
        BillingPort port = mock(BillingPort.class);
        when(port.getPricing(anyString())).thenReturn(Optional.of(ModelPricing.builder()
                .inputPricePerK(BigDecimal.TEN).outputPricePerK(BigDecimal.TEN).build()));
        when(port.getOrCreateWallet(1L, null)).thenReturn(UserWallet.builder()
                .userId(1L).balancePoints(0L).walletStatus("NORMAL").build());
        assertThrows(WalletInsufficientException.class, () -> new WalletBillingService(port)
                .deductTokenPoints(1L, "session", "TEST", "DEEPSEEK", "deepseek-chat", 100, 100, 1));
        verify(port, never()).recordUsageLedger(any());
    }

    private BillingPort mockBillingPort;
    private WalletBillingService billingService;

    @BeforeEach
    void setUp() {
        mockBillingPort = Mockito.mock(BillingPort.class);
        billingService = new WalletBillingService(mockBillingPort);
    }

    @Test
    @DisplayName("验证投研前置配额探测与欠费拦截")
    void testCheckBalance() {
        // 场景 1: 余额充裕 (10,000 点)
        UserWallet normalWallet = UserWallet.builder()
                .userId(1L)
                .balancePoints(10000L)
                .walletStatus("NORMAL")
                .build();
        when(mockBillingPort.getOrCreateWallet(1L, null)).thenReturn(normalWallet);

        assertDoesNotThrow(() -> billingService.checkBalance(1L, 100L));

        // 场景 2: 余额不足 (仅 50 点，门槛 100 点)
        UserWallet lowWallet = UserWallet.builder()
                .userId(2L)
                .balancePoints(50L)
                .walletStatus("NORMAL")
                .build();
        when(mockBillingPort.getOrCreateWallet(2L, null)).thenReturn(lowWallet);

        WalletInsufficientException ex = assertThrows(WalletInsufficientException.class,
                () -> billingService.checkBalance(2L, 100L));
        assertEquals(2L, ex.getUserId());
        assertEquals(50L, ex.getCurrentBalance());
        assertEquals(100L, ex.getRequiredPoints());
    }

    @Test
    @DisplayName("验证模型计价点数计算逻辑")
    void testCalculatePoints() {
        ModelPricing pricing = ModelPricing.builder()
                .inputPricePerK(new BigDecimal("10.0"))
                .outputPricePerK(new BigDecimal("20.0"))
                .build();

        when(mockBillingPort.getPricing("deepseek-chat")).thenReturn(Optional.of(pricing));

        // 1,000 输入 tokens (10点) + 2,000 输出 tokens (40点) = 50 点
        long points = billingService.calculatePoints("deepseek-chat", 1000, 2000);
        assertEquals(50L, points);
    }

    @Test
    @DisplayName("验证 Token 扣费与记账流水落库")
    void testDeductTokenPoints() {
        ModelPricing pricing = ModelPricing.builder()
                .inputPricePerK(new BigDecimal("10.0"))
                .outputPricePerK(new BigDecimal("20.0"))
                .build();
        when(mockBillingPort.getPricing(anyString())).thenReturn(Optional.of(pricing));
        when(mockBillingPort.deductPoints(eq(1L), anyLong())).thenReturn(true);

        UserWallet remainingWallet = UserWallet.builder()
                .userId(1L)
                .balancePoints(99950L)
                .walletStatus("NORMAL")
                .build();
        when(mockBillingPort.getOrCreateWallet(eq(1L), any())).thenReturn(remainingWallet);

        TokenDeductionResult result = billingService.deductTokenPoints(
                1L, "sess-123", "SYNTHESIS", "DEEPSEEK", "deepseek-chat",
                1000, 2000, 1500L
        );

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(50L, result.getConsumedPoints());
        assertEquals(99950L, result.getRemainingBalancePoints());
        assertEquals(3000, result.getTotalTokens());
        assertEquals(1500L, result.getLatencyMs());

        verify(mockBillingPort, times(1)).recordUsageLedger(any(TokenUsageLedger.class));
    }

    @Test
    @DisplayName("验证在线充值下单与支付回调到账闭环")
    void testRechargeOrderLifecycle() {
        RechargePackage pkg = RechargePackage.builder()
                .id(2L)
                .packageName("专业分析师包")
                .priceCny(new BigDecimal("199.00"))
                .grantedPoints(2000000L)
                .bonusPoints(200000L)
                .build();

        when(mockBillingPort.getPackageById(2L)).thenReturn(Optional.of(pkg));

        // 1. 创建订单
        RechargeOrder order = billingService.createOrder(1L, 2L, "ALIPAY");
        assertNotNull(order);
        assertTrue(order.getOrderNo().startsWith("ORD"));
        assertEquals("PENDING", order.getOrderStatus());
        assertEquals(2200000L, order.getTargetPoints());
        verify(mockBillingPort, times(1)).saveOrder(any(RechargeOrder.class));

        // 2. 模拟支付回调
        when(mockBillingPort.getOrderByNoForUpdate(order.getOrderNo())).thenReturn(Optional.of(order));

        RechargeOrder paidOrder = billingService.payCallback(order.getOrderNo(), "ALIPAY-TRADE-9999", order.getPayAmountCny());
        assertEquals("PAID", paidOrder.getOrderStatus());
        assertEquals("ALIPAY-TRADE-9999", paidOrder.getThirdPartyTradeNo());
        assertNotNull(paidOrder.getPaidAt());

        verify(mockBillingPort, times(1)).addRechargePoints(1L, 2200000L);
        verify(mockBillingPort, times(1)).updateOrder(order);
    }

    @Test
    @DisplayName("验证钱包展示 DTO 与消费流水清单检索")
    void testWalletAndLedgerQueries() {
        UserWallet wallet = UserWallet.builder()
                .userId(1L)
                .balancePoints(86400L)
                .totalRechargedPoints(100000L)
                .totalConsumedPoints(13600L)
                .walletStatus("NORMAL")
                .build();
        when(mockBillingPort.getOrCreateWallet(1L, null)).thenReturn(wallet);

        WalletDTO dto = billingService.getWallet(1L);
        assertNotNull(dto);
        assertEquals(86400L, dto.getBalancePoints());
        assertEquals(43L, dto.getEstimatedReportsRemaining()); // 86400 / 2000

        when(mockBillingPort.queryLedger(eq(1L), anyInt(), anyInt(), any(), any()))
                .thenReturn(List.of(TokenUsageLedger.builder().id(101L).build()));
        when(mockBillingPort.countLedger(eq(1L), any(), any())).thenReturn(1L);

        Map<String, Object> ledgerPage = billingService.getLedger(1L, 1, 10, null, null);
        assertEquals(1L, ledgerPage.get("total"));
        assertEquals(1, ((List<?>) ledgerPage.get("list")).size());
    }
}
