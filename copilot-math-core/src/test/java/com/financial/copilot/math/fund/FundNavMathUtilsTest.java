package com.financial.copilot.math.fund;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>基金专有量化数学计算工具单元测试 (Fund Nav Math Utils Test)</h1>
 * <p>
 * 测试验证基金跟踪误差 (Tracking Error)、信息比率 (Information Ratio) 以及上下行捕获率。
 * </p>
 *
 * @author FinancialCopilot
 */
class FundNavMathUtilsTest {

    /**
     * 测试验证年化跟踪误差计算
     */
    @Test
    @DisplayName("验证基金年化跟踪误差计算准确度")
    void testCalculateTrackingError() {
        List<BigDecimal> fundReturns = List.of(
                new BigDecimal("1.20"),
                new BigDecimal("-0.80"),
                new BigDecimal("0.50"),
                new BigDecimal("1.10"),
                new BigDecimal("-0.40")
        );
        List<BigDecimal> benchReturns = List.of(
                new BigDecimal("1.00"),
                new BigDecimal("-0.50"),
                new BigDecimal("0.60"),
                new BigDecimal("0.90"),
                new BigDecimal("-0.30")
        );

        BigDecimal te = FundNavMathUtils.calculateTrackingError(fundReturns, benchReturns);
        assertNotNull(te);
        assertTrue(te.compareTo(BigDecimal.ZERO) > 0, "跟踪误差应为正数");
    }

    /**
     * 测试验证信息比率计算
     */
    @Test
    @DisplayName("验证信息比率计算")
    void testCalculateInformationRatio() {
        BigDecimal excessReturn = new BigDecimal("8.50");
        BigDecimal trackingError = new BigDecimal("4.25");

        BigDecimal ir = FundNavMathUtils.calculateInformationRatio(excessReturn, trackingError);
        assertNotNull(ir);
        assertEquals(new BigDecimal("2.00"), ir, "8.50 / 4.25 应等于 2.00");
    }

    /**
     * 测试验证上下行捕获率
     */
    @Test
    @DisplayName("验证上行与下行捕获比率")
    void testCaptureRatios() {
        BigDecimal fundUp = new BigDecimal("15.00");
        BigDecimal benchUp = new BigDecimal("12.00");
        BigDecimal upRatio = FundNavMathUtils.calculateUpCaptureRatio(fundUp, benchUp);
        assertEquals(new BigDecimal("125.00"), upRatio, "15 / 12 * 100 应为 125.00%");

        BigDecimal fundDown = new BigDecimal("-8.00");
        BigDecimal benchDown = new BigDecimal("-10.00");
        BigDecimal downRatio = FundNavMathUtils.calculateDownCaptureRatio(fundDown, benchDown);
        assertEquals(new BigDecimal("80.00"), downRatio, "-8 / -10 * 100 应为 80.00%");
    }
}
