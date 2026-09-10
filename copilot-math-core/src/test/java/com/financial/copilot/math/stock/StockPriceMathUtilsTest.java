package com.financial.copilot.math.stock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>股票量化计算工具单元测试 (Stock Price Math Utils Test)</h1>
 * <p>
 * 测试验证股票 Beta 系数、价格动量及 VWAP 成交量加权平均价计算。
 * </p>
 *
 * @author FinancialCopilot
 */
class StockPriceMathUtilsTest {

    /**
     * 测试验证 Beta 系数计算
     */
    @Test
    @DisplayName("验证股票 Beta 系数计算")
    void testCalculateBeta() {
        List<BigDecimal> stockReturns = List.of(
                new BigDecimal("2.0"), new BigDecimal("-1.0"), new BigDecimal("3.0"), new BigDecimal("0.5")
        );
        List<BigDecimal> marketReturns = List.of(
                new BigDecimal("1.0"), new BigDecimal("-0.5"), new BigDecimal("1.5"), new BigDecimal("0.25")
        );

        BigDecimal beta = StockPriceMathUtils.calculateBeta(stockReturns, marketReturns);
        assertNotNull(beta);
        assertTrue(beta.compareTo(BigDecimal.ZERO) > 0, "Beta 应当大于 0");
    }

    /**
     * 测试验证价格动量计算
     */
    @Test
    @DisplayName("验证价格动量计算")
    void testCalculatePriceMomentum() {
        BigDecimal start = new BigDecimal("100.00");
        BigDecimal end = new BigDecimal("125.50");

        BigDecimal momentum = StockPriceMathUtils.calculatePriceMomentum(start, end);
        assertEquals(new BigDecimal("25.50"), momentum, "100 涨至 125.50 动量应为 25.50%");
    }

    /**
     * 测试验证 VWAP 计算
     */
    @Test
    @DisplayName("验证 VWAP 计算")
    void testCalculateVWAP() {
        List<BigDecimal> prices = List.of(new BigDecimal("10.0"), new BigDecimal("12.0"));
        List<BigDecimal> volumes = List.of(new BigDecimal("100"), new BigDecimal("200"));

        BigDecimal vwap = StockPriceMathUtils.calculateVWAP(prices, volumes);
        // (10*100 + 12*200) / 300 = (1000 + 2400) / 300 = 3400 / 300 = 11.33
        assertEquals(new BigDecimal("11.33"), vwap);
    }
}
