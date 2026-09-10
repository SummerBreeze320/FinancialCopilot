package com.financial.copilot.math;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * 金融量化指标高精度数学计算工具
 * 遵循 Tool-as-Truth 规范，为所有 Agent 提供无幻觉的确定性计算支撑。
 */
public final class FinancialMathUtils {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");

    private FinancialMathUtils() {
        // 私有构造防实例化
    }

    /**
     * 计算区间累计收益率 (%)
     * 公式: ((期末净值 - 期初净值) / 期初净值) * 100
     */
    public static BigDecimal calculateCumulativeReturn(BigDecimal startNav, BigDecimal endNav) {
        if (startNav == null || endNav == null || startNav.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return endNav.subtract(startNav)
                .divide(startNav, 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算年化复合收益率 (%)
     * 公式: ((期末净值 / 期初净值) ^ (365 / 持有天数) - 1) * 100
     */
    public static BigDecimal calculateAnnualizedReturn(BigDecimal startNav, BigDecimal endNav, long days) {
        if (startNav == null || endNav == null || startNav.compareTo(BigDecimal.ZERO) <= 0 || days <= 0) {
            return BigDecimal.ZERO;
        }
        double ratio = endNav.divide(startNav, 8, RoundingMode.HALF_UP).doubleValue();
        double exponent = (double) 365 / days;
        double annualizedRatio = Math.pow(ratio, exponent) - 1.0;

        return BigDecimal.valueOf(annualizedRatio * 100).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算指定净值序列的最大回撤 (%)
     * 算法: 实时跟踪最高峰值，回撤 = (Peak_t - NAV_t) / Peak_t
     */
    public static BigDecimal calculateMaxDrawdown(List<BigDecimal> navSeries) {
        if (navSeries == null || navSeries.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal peak = navSeries.get(0);
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (BigDecimal currentNav : navSeries) {
            if (currentNav == null) {
                continue;
            }
            if (currentNav.compareTo(peak) > 0) {
                peak = currentNav;
            } else if (peak.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal currentDrawdown = peak.subtract(currentNav)
                        .divide(peak, 6, RoundingMode.HALF_UP);
                if (currentDrawdown.compareTo(maxDrawdown) > 0) {
                    maxDrawdown = currentDrawdown;
                }
            }
        }

        return maxDrawdown.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算夏普比率 (Sharpe Ratio)
     * 公式: (年化收益率 - 无风险利率) / 年化波动率
     */
    public static BigDecimal calculateSharpeRatio(BigDecimal annualizedReturn,
                                                  BigDecimal riskFreeRate,
                                                  BigDecimal annualizedVolatility) {
        if (annualizedReturn == null || annualizedVolatility == null || annualizedVolatility.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal rf = (riskFreeRate == null) ? BigDecimal.ZERO : riskFreeRate;
        return annualizedReturn.subtract(rf)
                .divide(annualizedVolatility, 4, RoundingMode.HALF_UP);
    }

    /**
     * 计算卡玛比率 (Calmar Ratio)
     * 公式: 年化收益率 / 最大回撤
     */
    public static BigDecimal calculateCalmarRatio(BigDecimal annualizedReturn, BigDecimal maxDrawdown) {
        if (annualizedReturn == null || maxDrawdown == null || maxDrawdown.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return annualizedReturn.divide(maxDrawdown, 4, RoundingMode.HALF_UP);
    }
}
