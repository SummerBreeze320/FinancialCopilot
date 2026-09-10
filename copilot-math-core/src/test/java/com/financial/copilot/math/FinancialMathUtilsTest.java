package com.financial.copilot.math;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("金融量化指标高精度数学计算引擎测试")
class FinancialMathUtilsTest {

    @Test
    @DisplayName("测试累计收益率计算: 净值从 1.0000 涨到 1.5000，收益率应为 50.00%")
    void testCalculateCumulativeReturn() {
        BigDecimal startNav = new BigDecimal("1.0000");
        BigDecimal endNav = new BigDecimal("1.5000");

        BigDecimal returnRate = FinancialMathUtils.calculateCumulativeReturn(startNav, endNav);

        assertNotNull(returnRate);
        assertEquals(new BigDecimal("50.00"), returnRate);
    }

    @Test
    @DisplayName("测试年化复合收益率计算: 365天翻倍(1.0到2.0)，年化复合回报率应为 100.00%")
    void testCalculateAnnualizedReturn() {
        BigDecimal startNav = new BigDecimal("1.0000");
        BigDecimal endNav = new BigDecimal("2.0000");
        long days = 365;

        BigDecimal annualizedReturn = FinancialMathUtils.calculateAnnualizedReturn(startNav, endNav, days);

        assertNotNull(annualizedReturn);
        assertEquals(new BigDecimal("100.00"), annualizedReturn);
    }

    @Test
    @DisplayName("测试最大回撤计算: 净值序列 [1.0, 1.2, 1.5, 1.05, 1.3]，最高峰1.5跌到1.05，回撤=(1.5-1.05)/1.5=30.00%")
    void testCalculateMaxDrawdown() {
        List<BigDecimal> navSeries = Arrays.asList(
                new BigDecimal("1.0000"),
                new BigDecimal("1.2000"),
                new BigDecimal("1.5000"), // 峰值
                new BigDecimal("1.0500"), // 低谷
                new BigDecimal("1.3000")
        );

        BigDecimal maxDrawdown = FinancialMathUtils.calculateMaxDrawdown(navSeries);

        assertNotNull(maxDrawdown);
        assertEquals(new BigDecimal("30.00"), maxDrawdown);
    }

    @Test
    @DisplayName("测试单调上涨的净值序列，最大回撤应为 0.00%")
    void testCalculateMaxDrawdownMonotonic() {
        List<BigDecimal> navSeries = Arrays.asList(
                new BigDecimal("1.0000"),
                new BigDecimal("1.1000"),
                new BigDecimal("1.2000"),
                new BigDecimal("1.3000")
        );

        BigDecimal maxDrawdown = FinancialMathUtils.calculateMaxDrawdown(navSeries);

        assertNotNull(maxDrawdown);
        assertEquals(new BigDecimal("0.00"), maxDrawdown);
    }

    @Test
    @DisplayName("测试夏普比率计算: 年化收益20%，无风险利率2.5%，年化波动率15%，夏普 = (20 - 2.5) / 15 = 1.1667")
    void testCalculateSharpeRatio() {
        BigDecimal annualizedReturn = new BigDecimal("20.00");
        BigDecimal riskFreeRate = new BigDecimal("2.50");
        BigDecimal annualizedVolatility = new BigDecimal("15.00");

        BigDecimal sharpe = FinancialMathUtils.calculateSharpeRatio(annualizedReturn, riskFreeRate, annualizedVolatility);

        assertNotNull(sharpe);
        assertEquals(new BigDecimal("1.1667"), sharpe);
    }

    @Test
    @DisplayName("测试卡玛比率计算: 年化收益15%，最大回撤10%，卡玛 = 15 / 10 = 1.5000")
    void testCalculateCalmarRatio() {
        BigDecimal annualizedReturn = new BigDecimal("15.00");
        BigDecimal maxDrawdown = new BigDecimal("10.00");

        BigDecimal calmar = FinancialMathUtils.calculateCalmarRatio(annualizedReturn, maxDrawdown);

        assertNotNull(calmar);
        assertEquals(new BigDecimal("1.5000"), calmar);
    }

    @Test
    @DisplayName("边界测试: 空序列或零回撤输入，防除零异常")
    void testEdgeCases() {
        assertEquals(BigDecimal.ZERO, FinancialMathUtils.calculateMaxDrawdown(Collections.emptyList()));
        assertEquals(BigDecimal.ZERO, FinancialMathUtils.calculateSharpeRatio(new BigDecimal("10.00"), new BigDecimal("2.00"), BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, FinancialMathUtils.calculateCalmarRatio(new BigDecimal("10.00"), BigDecimal.ZERO));
    }
}
