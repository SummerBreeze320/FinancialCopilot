package com.financial.copilot.math.stock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>股票量化计算工具单元测试 (Stock Price Math Utils Test)</h1>
 * <p>
 * 测试验证股票 Beta 系数、价格动量、VWAP、RSI、MACD、SMA/EMA 与年化波动率计算。
 * </p>
 *
 * @author FinancialCopilot
 */
class StockPriceMathUtilsTest {

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

    @Test
    @DisplayName("验证价格动量计算")
    void testCalculatePriceMomentum() {
        BigDecimal start = new BigDecimal("100.00");
        BigDecimal end = new BigDecimal("125.50");
        BigDecimal momentum = StockPriceMathUtils.calculatePriceMomentum(start, end);
        assertEquals(new BigDecimal("25.50"), momentum, "100 涨至 125.50 动量应为 25.50%");
    }

    @Test
    @DisplayName("验证 VWAP 计算")
    void testCalculateVWAP() {
        List<BigDecimal> prices = List.of(new BigDecimal("10.0"), new BigDecimal("12.0"));
        List<BigDecimal> volumes = List.of(new BigDecimal("100"), new BigDecimal("200"));
        BigDecimal vwap = StockPriceMathUtils.calculateVWAP(prices, volumes);
        assertEquals(new BigDecimal("11.33"), vwap);
    }

    @Test
    @DisplayName("验证 SMA 计算")
    void testCalculateSMA() {
        List<BigDecimal> prices = List.of(
                new BigDecimal("10.0"), new BigDecimal("11.0"), new BigDecimal("12.0"),
                new BigDecimal("13.0"), new BigDecimal("14.0")
        );
        BigDecimal sma5 = StockPriceMathUtils.calculateSMA(prices, 5);
        assertNotNull(sma5);
        assertEquals(new BigDecimal("12.00"), sma5, "SMA(5) 应为 (10+11+12+13+14)/5 = 12.00");
    }

    @Test
    @DisplayName("验证 SMA 数据不足时返回 null")
    void testCalculateSMA_insufficientData() {
        List<BigDecimal> prices = List.of(new BigDecimal("10.0"), new BigDecimal("11.0"));
        assertNull(StockPriceMathUtils.calculateSMA(prices, 5));
    }

    @Test
    @DisplayName("验证 EMA 计算")
    void testCalculateEMA() {
        List<BigDecimal> prices = List.of(
                new BigDecimal("10.0"), new BigDecimal("11.0"), new BigDecimal("12.0"),
                new BigDecimal("13.0"), new BigDecimal("14.0"), new BigDecimal("15.0")
        );
        BigDecimal ema3 = StockPriceMathUtils.calculateEMA(prices, 3);
        assertNotNull(ema3);
        assertTrue(ema3.compareTo(new BigDecimal("13.0")) > 0, "EMA 应接近最新价格");
    }

    @Test
    @DisplayName("验证 RSI 计算 (上涨趋势应接近 100)")
    void testCalculateRSI() {
        // 构造一个持续上涨的价格序列
        List<BigDecimal> prices = new ArrayList<>();
        BigDecimal p = new BigDecimal("100.00");
        prices.add(p);
        for (int i = 0; i < 20; i++) {
            p = p.add(new BigDecimal("1.00"));
            prices.add(p);
        }
        BigDecimal rsi = StockPriceMathUtils.calculateRSI(prices, 14);
        assertNotNull(rsi);
        assertTrue(rsi.compareTo(new BigDecimal("90")) > 0, "持续上涨时 RSI 应接近 100");
    }

    @Test
    @DisplayName("验证 RSI 数据不足时返回 null")
    void testCalculateRSI_insufficientData() {
        List<BigDecimal> prices = List.of(new BigDecimal("100.0"), new BigDecimal("101.0"));
        assertNull(StockPriceMathUtils.calculateRSI(prices, 14));
    }

    @Test
    @DisplayName("验证 MACD 计算")
    void testCalculateMACD() {
        // 构造 50 个价格数据
        List<BigDecimal> prices = new ArrayList<>();
        BigDecimal p = new BigDecimal("100.00");
        prices.add(p);
        for (int i = 0; i < 49; i++) {
            p = p.add(new BigDecimal("0.50"));
            prices.add(p);
        }
        double[] macd = StockPriceMathUtils.calculateMACD(prices, 12, 26, 9);
        assertNotNull(macd, "MACD 应返回 3 个值");
        assertEquals(3, macd.length);
        // DIF, DEA, MACD柱 均应有值
        assertTrue(macd[0] != 0 || macd[1] != 0, "DIF 或 DEA 应非零");
    }

    @Test
    @DisplayName("验证年化波动率计算")
    void testCalculateAnnualizedVolatility() {
        List<BigDecimal> prices = new ArrayList<>();
        BigDecimal p = new BigDecimal("100.00");
        prices.add(p);
        for (int i = 0; i < 60; i++) {
            // 模拟日波动 ±1%
            BigDecimal change = (i % 2 == 0) ? new BigDecimal("1.00") : new BigDecimal("-0.80");
            p = p.add(change);
            prices.add(p);
        }
        BigDecimal vol = StockPriceMathUtils.calculateAnnualizedVolatility(prices);
        assertNotNull(vol);
        assertTrue(vol.compareTo(BigDecimal.ZERO) > 0, "波动率应为正数");
        assertTrue(vol.compareTo(new BigDecimal("50")) < 0, "波动率不应超过 50%");
    }

    @Test
    @DisplayName("验证年化波动率数据不足时返回 null")
    void testCalculateAnnualizedVolatility_insufficientData() {
        List<BigDecimal> prices = List.of(new BigDecimal("100.0"));
        assertNull(StockPriceMathUtils.calculateAnnualizedVolatility(prices));
    }
}
