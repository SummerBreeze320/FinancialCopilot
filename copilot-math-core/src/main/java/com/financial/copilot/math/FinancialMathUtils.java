package com.financial.copilot.math;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * <h1>通用金融量化指标高精度数学计算工具 (Financial Math Utils)</h1>
 * <p>
 * 职责：遵循 Tool-as-Truth 规范，为基金、股票等多资产领域的量化分析提供无幻觉、高精度、确定性的底层数学支撑。
 * 支持累计收益率、年化复合收益率、最大回撤、夏普比率、卡玛比率等通用核心金融指标。
 * </p>
 *
 * @author FinancialCopilot
 */
public final class FinancialMathUtils {

    /**
     * 百分比基数 100
     */
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * 年自然日基准 365 天
     */
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");

    private FinancialMathUtils() {
        // 私有构造防实例化
    }

    /**
     * 计算区间累计收益率 (%)
     * <p>
     * 公式: $\frac{NAV_{end} - NAV_{start}}{NAV_{start}} \times 100$
     * </p>
     *
     * @param startNav 期初净值或期初价格
     * @param endNav   期末净值或期末价格
     * @return 累计收益率百分比（如 25.40 表示 25.40%）
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
     * 计算年化复合收益率 (CAGR %)
     * <p>
     * 公式: $[(\frac{NAV_{end}}{NAV_{start}})^{\frac{365}{days}} - 1] \times 100$
     * </p>
     *
     * @param startNav 期初净值或价格
     * @param endNav   期末净值或价格
     * @param days     区间跨越自然日天数
     * @return 年化收益率百分比（保留 2 位小数）
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
     * 计算时序序列的最大回撤 (Max Drawdown, %)
     * <p>
     * 算法：实时追踪序列的历史最高峰值 $Peak_t$，计算当前回撤 $\frac{Peak_t - NAV_t}{Peak_t}$ 并求全局最大值。
     * </p>
     *
     * @param navSeries 净值或价格时序序列
     * @return 最大回撤百分比（正数形式，如 18.50 表示最大回撤为 18.50%）
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
     * <p>
     * 公式: $\frac{R_p - R_f}{\sigma_p}$
     * </p>
     *
     * @param annualizedReturn     资产年化复合收益率 (%)
     * @param riskFreeRate         无风险年化利率 (如 2.0 表示 2.0%)
     * @param annualizedVolatility 资产年化波动率 (%)
     * @return 夏普比率（保留 4 位小数）
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
     * <p>
     * 公式: $\frac{R_p}{MaxDrawdown}$
     * 衡量每承担单位最大回撤所换取的年化收益。
     * </p>
     *
     * @param annualizedReturn 年化复合收益率 (%)
     * @param maxDrawdown      区间最大回撤 (%)
     * @return 卡玛比率（保留 4 位小数）
     */
    public static BigDecimal calculateCalmarRatio(BigDecimal annualizedReturn, BigDecimal maxDrawdown) {
        if (annualizedReturn == null || maxDrawdown == null || maxDrawdown.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return annualizedReturn.divide(maxDrawdown, 4, RoundingMode.HALF_UP);
    }
}
