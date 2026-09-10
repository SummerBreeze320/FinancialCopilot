package com.financial.copilot.controller.billing;

import com.financial.copilot.agent.core.billing.WalletBillingService;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.domain.billing.dto.RechargeOrderCreateDTO;
import com.financial.copilot.domain.billing.dto.UsageTrendPointDTO;
import com.financial.copilot.domain.billing.dto.WalletDTO;
import com.financial.copilot.domain.billing.entity.ModelPricing;
import com.financial.copilot.domain.billing.entity.RechargeOrder;
import com.financial.copilot.domain.billing.entity.RechargePackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * <h1>商业化计量计费 REST 控制器单元测试 (Billing Controller Test)</h1>
 *
 * @author FinancialCopilot
 */
class BillingControllerTest {

    private WalletBillingService mockBillingService;
    private BillingController controller;

    @BeforeEach
    void setUp() {
        mockBillingService = Mockito.mock(WalletBillingService.class);
        controller = new BillingController(mockBillingService);
    }

    @Test
    @DisplayName("验证钱包资产查询端点")
    void testGetWallet() {
        WalletDTO walletDTO = WalletDTO.builder()
                .userId(1L)
                .balancePoints(86400L)
                .estimatedCny(new BigDecimal("8.64"))
                .walletStatus("NORMAL")
                .estimatedReportsRemaining(43L)
                .build();
        when(mockBillingService.getWallet(1L)).thenReturn(walletDTO);

        ApiResult<WalletDTO> result = controller.getWallet(1L).block();
        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertEquals(86400L, result.getData().getBalancePoints());
        assertEquals(43L, result.getData().getEstimatedReportsRemaining());
    }

    @Test
    @DisplayName("验证充值套餐规格列表查询")
    void testListPackages() {
        when(mockBillingService.listPackages()).thenReturn(List.of(
                RechargePackage.builder().id(1L).packageName("投研尝鲜包").priceCny(new BigDecimal("49.00")).build()
        ));

        ApiResult<List<RechargePackage>> result = controller.listPackages();
        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals("投研尝鲜包", result.getData().get(0).getPackageName());
    }

    @Test
    @DisplayName("验证创建充值订单端点")
    void testCreateOrder() {
        RechargeOrderCreateDTO req = RechargeOrderCreateDTO.builder()
                .packageId(2L)
                .payChannel("WECHAT")
                .build();

        RechargeOrder order = RechargeOrder.builder()
                .orderNo("ORD20260910001")
                .userId(1L)
                .packageId(2L)
                .orderStatus("PENDING")
                .build();

        when(mockBillingService.createOrder(1L, 2L, "WECHAT")).thenReturn(order);

        ApiResult<RechargeOrder> result = controller.createOrder(req, 1L).block();
        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertEquals("ORD20260910001", result.getData().getOrderNo());
    }

    @Test
    @DisplayName("验证支付回调处理端点")
    void testPayCallback() {
        Map<String, String> payload = Map.of(
                "orderNo", "ORD20260910001",
                "thirdPartyTradeNo", "WX-PAY-8888"
        );

        RechargeOrder paidOrder = RechargeOrder.builder()
                .orderNo("ORD20260910001")
                .orderStatus("PAID")
                .thirdPartyTradeNo("WX-PAY-8888")
                .build();

        when(mockBillingService.payCallback("ORD20260910001", "WX-PAY-8888")).thenReturn(paidOrder);

        ApiResult<RechargeOrder> result = controller.payCallback(payload);
        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertEquals("PAID", result.getData().getOrderStatus());
    }

    @Test
    @DisplayName("验证对账明细、时序走势与公开计价端点")
    void testQueries() {
        when(mockBillingService.getLedger(anyLong(), anyInt(), anyInt(), any(), any()))
                .thenReturn(Map.of("total", 5L, "list", List.of()));
        when(mockBillingService.getUsageTrend(anyLong(), anyInt()))
                .thenReturn(List.of(UsageTrendPointDTO.builder().statDate("2026-09-10").build()));
        when(mockBillingService.listPricing())
                .thenReturn(List.of(ModelPricing.builder().modelName("deepseek-chat").build()));

        ApiResult<Map<String, Object>> ledgerResult = controller.getLedger(1L, 1, 10, null, null).block();
        assertNotNull(ledgerResult);
        assertEquals(200, ledgerResult.getCode());

        ApiResult<List<UsageTrendPointDTO>> trendResult = controller.getUsageTrend(1L, 7).block();
        assertNotNull(trendResult);
        assertEquals(200, trendResult.getCode());
        assertEquals(1, trendResult.getData().size());

        ApiResult<List<ModelPricing>> pricingResult = controller.getPricing();
        assertEquals(200, pricingResult.getCode());
        assertEquals(1, pricingResult.getData().size());
    }
}
