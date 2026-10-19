package com.financial.copilot.math.stock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>股票估值分析工具单元测试 (Stock Valuation Utils Test)</h1>
 *
 * @author FinancialCopilot
 */
class StockValuationUtilsTest {

    @Test
    @DisplayName("验证 PE 分位数计算 (中位数位置)")
    void testCalculatePEPercentile() {
        List<BigDecimal> historicalPEs = List.of(
                new BigDecimal("10"), new BigDecimal("15"), new BigDecimal("20"),
                new BigDecimal("25"), new BigDecimal("30"), new BigDecimal("35"),
                new BigDecimal("40"), new BigDecimal("45"), new BigDecimal("50"), new BigDecimal("55")
        );
        BigDecimal currentPE = new BigDecimal("30");
        BigDecimal percentile = StockValuationUtils.calculatePEPercentile(historicalPEs, currentPE);
        assertNotNull(percentile);
        // 30 在 10 个值中，<=30 的有 5 个 (10,15,20,25,30)，分位数应为 50%
        assertEquals(new BigDecimal("50.00"), percentile);
    }

    @Test
    @DisplayName("验证 PE 分位数低估场景")
    void testCalculatePEPercentile_lowValuation() {
        List<BigDecimal> historicalPEs = List.of(
                new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30"),
                new BigDecimal("40"), new BigDecimal("50")
        );
        BigDecimal currentPE = new BigDecimal("12");
        BigDecimal percentile = StockValuationUtils.calculatePEPercentile(historicalPEs, currentPE);
        assertNotNull(percentile);
        assertTrue(percentile.compareTo(new BigDecimal("40")) < 0, "低估值时分位数应 < 40%");
    }

    @Test
    @DisplayName("验证 PB 分位数计算")
    void testCalculatePBPercentile() {
        List<BigDecimal> historicalPBs = List.of(
                new BigDecimal("1.0"), new BigDecimal("2.0"), new BigDecimal("3.0"),
                new BigDecimal("4.0"), new BigDecimal("5.0")
        );
        BigDecimal currentPB = new BigDecimal("4.5");
        BigDecimal percentile = StockValuationUtils.calculatePBPercentile(historicalPBs, currentPB);
        assertNotNull(percentile);
        assertEquals(new BigDecimal("80.00"), percentile);
    }

    @Test
    @DisplayName("验证 PEG 计算")
    void testCalculatePEG() {
        BigDecimal pe = new BigDecimal("30.00");
        BigDecimal growth = new BigDecimal("25.00");
        BigDecimal peg = StockValuationUtils.calculatePEG(pe, growth);
        assertNotNull(peg);
        // PEG = 30 / 25 = 1.20
        assertEquals(new BigDecimal("1.20"), peg);
    }

    @Test
    @DisplayName("验证 PEG 增速为 0 时返回 null")
    void testCalculatePEG_zeroGrowth() {
        assertNull(StockValuationUtils.calculatePEG(new BigDecimal("20"), BigDecimal.ZERO));
        assertNull(StockValuationUtils.calculatePEG(new BigDecimal("20"), null));
    }

    @Test
    @DisplayName("验证简化 DCF 模型")
    void testCalculateSimplifiedDCF() {
        // 贵州茅台模拟: FCF=400亿, 高增长15%持续5年, 永续3%, WACC 10%, 总股本12.56亿股
        BigDecimal value = StockValuationUtils.calculateSimplifiedDCF(
                new BigDecimal("400.00"),
                new BigDecimal("15.00"),
                5,
                new BigDecimal("3.00"),
                new BigDecimal("10.00"),
                new BigDecimal("12.56")
        );
        assertNotNull(value);
        assertTrue(value.compareTo(new BigDecimal("100")) > 0, "每股内在价值应大于 100 元");
        assertTrue(value.compareTo(new BigDecimal("3000")) < 0, "每股内在价值应合理 (<3000)");
    }

    @Test
    @DisplayName("验证 DCF 参数异常时返回 null")
    void testCalculateSimplifiedDCF_invalidParams() {
        assertNull(StockValuationUtils.calculateSimplifiedDCF(
                BigDecimal.ZERO, new BigDecimal("10"), 5, new BigDecimal("3"), new BigDecimal("10"), new BigDecimal("10")
        ));
        assertNull(StockValuationUtils.calculateSimplifiedDCF(
                new BigDecimal("100"), new BigDecimal("5"), 5, new BigDecimal("10"), new BigDecimal("8"), new BigDecimal("10")
        ));
    }

    @Test
    @DisplayName("验证 Gordon DDM 股息折现模型")
    void testCalculateGordonDDM() {
        // 每股股息 5 元, 增速 5%, 要求回报率 10%
        BigDecimal value = StockValuationUtils.calculateGordonDDM(
                new BigDecimal("5.00"),
                new BigDecimal("5.00"),
                new BigDecimal("10.00")
        );
        assertNotNull(value);
        // V = D1 / (r - g) = 5 * 1.05 / (0.10 - 0.05) = 5.25 / 0.05 = 105.00
        assertEquals(new BigDecimal("105.00"), value);
    }

    @Test
    @DisplayName("验证 Gordon DDM 参数异常时返回 null")
    void testCalculateGordonDDM_invalidParams() {
        assertNull(StockValuationUtils.calculateGordonDDM(BigDecimal.ZERO, new BigDecimal("5"), new BigDecimal("10")));
        assertNull(StockValuationUtils.calculateGordonDDM(new BigDecimal("5"), new BigDecimal("12"), new BigDecimal("10")));
    }
}
