package com.financial.copilot.math.fund;

import com.financial.copilot.math.FinancialMathUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * <h1>公募基金专有量化数学计算工具 (Fund NAV Math Utils)</h1>
 * <p>
 * 职责：专为公募基金领域定制的高精度量化数学指标计算库，涵盖：
 * 1. 跟踪误差 (Tracking Error)
 * 2. 信息比率 (Information Ratio)
 * 3. 基金上行与下行捕获比率 (Up / Down Capture Ratio)
 * 4. 份额复权收益率
 * 遵循 Tool-as-Truth 规范，为 Fund 领域各类 Agent 提供无幻觉的确定性计算支撑。
 * </p>
 *
 * @author FinancialCopilot
 */
public final class FundNavMathUtils {

    /**
     * 年化交易日数基准（通常取 250 天）
     */
    private static final BigDecimal TRADING_DAYS_PER_YEAR = new BigDecimal("250");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private FundNavMathUtils() {
        // 私有构造，防止工具类实例化
    }

    /**
     * 计算公募基金相对于业绩基准的年化跟踪误差 (Tracking Error)
     * <p>
     * 公式: $TE = \sqrt{250} \times \sigma(R_{fund} - R_{bench})$
     * </p>
     *
     * @param fundDailyReturns  基金每日收益率序列（百分比形式，如 1.25 表示 1.25%）
     * @param benchDailyReturns 对应基准每日收益率序列
     * @return 年化跟踪误差（百分比，保留 2 位小数）
     */
    public static BigDecimal calculateTrackingError(List<BigDecimal> fundDailyReturns,
                                                    List<BigDecimal> benchDailyReturns) {
        if (fundDailyReturns == null || benchDailyReturns == null
                || fundDailyReturns.isEmpty() || fundDailyReturns.size() != benchDailyReturns.size()) {
            return BigDecimal.ZERO;
        }

        int n = fundDailyReturns.size();
        if (n < 2) {
            return BigDecimal.ZERO;
        }

        // 计算超额日收益序列: excess_t = r_fund,t - r_bench,t
        double[] excess = new double[n];
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            BigDecimal f = fundDailyReturns.get(i) != null ? fundDailyReturns.get(i) : BigDecimal.ZERO;
            BigDecimal b = benchDailyReturns.get(i) != null ? benchDailyReturns.get(i) : BigDecimal.ZERO;
            excess[i] = f.subtract(b).doubleValue();
            sum += excess[i];
        }

        double mean = sum / n;
        double varianceSum = 0.0;
        for (int i = 0; i < n; i++) {
            double diff = excess[i] - mean;
            varianceSum += diff * diff;
        }

        double sampleVariance = varianceSum / (n - 1);
        double dailyTe = Math.sqrt(sampleVariance);
        // 年化跟踪误差 = 日跟踪误差 * sqrt(250)
        double annualizedTe = dailyTe * Math.sqrt(250.0);

        return BigDecimal.valueOf(annualizedTe).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算公募基金信息比率 (Information Ratio)
     * <p>
     * 公式: $IR = \frac{\text{年化超额收益率}}{\text{年化跟踪误差}}$
     * 衡量基金经理在承担主动风险（主动偏离基准）的情况下，获取超额收益的能力。
     * </p>
     *
     * @param annualizedExcessReturn 年化超额收益率 (%)
     * @param annualizedTrackingError 年化跟踪误差 (%)
     * @return 信息比率 (保留 2 位小数)
     */
    public static BigDecimal calculateInformationRatio(BigDecimal annualizedExcessReturn,
                                                       BigDecimal annualizedTrackingError) {
        if (annualizedExcessReturn == null || annualizedTrackingError == null
                || annualizedTrackingError.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return annualizedExcessReturn.divide(annualizedTrackingError, 2, RoundingMode.HALF_UP);
    }

    /**
     * 计算公募基金上行捕获率 (Up Capture Ratio)
     * <p>
     * 衡量当基准上涨时，基金能捕获基准涨幅的百分比。大于 100% 表示顺境进攻性更强。
     * </p>
     *
     * @param fundReturnOnBenchUp   基准上涨期内基金的累计收益率 (%)
     * @param benchReturnOnBenchUp  基准在上涨期内的累计收益率 (%)
     * @return 上行捕获率百分比 (如 110.50 表示 110.50%)
     */
    public static BigDecimal calculateUpCaptureRatio(BigDecimal fundReturnOnBenchUp,
                                                    BigDecimal benchReturnOnBenchUp) {
        if (fundReturnOnBenchUp == null || benchReturnOnBenchUp == null
                || benchReturnOnBenchUp.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return fundReturnOnBenchUp.divide(benchReturnOnBenchUp, 4, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算公募基金下行捕获率 (Down Capture Ratio)
     * <p>
     * 衡量当基准下跌时，基金跟随下跌的百分比。小于 100% 表示逆境抗跌防御性更佳。
     * </p>
     *
     * @param fundReturnOnBenchDown   基准下跌期内基金的累计收益率 (%)
     * @param benchReturnOnBenchDown  基准在下跌期内的累计收益率 (%)
     * @return 下行捕获率百分比
     */
    public static BigDecimal calculateDownCaptureRatio(BigDecimal fundReturnOnBenchDown,
                                                      BigDecimal benchReturnOnBenchDown) {
        if (fundReturnOnBenchDown == null || benchReturnOnBenchDown == null
                || benchReturnOnBenchDown.compareTo(BigDecimal.ZERO) >= 0) {
            return BigDecimal.ZERO;
        }
        return fundReturnOnBenchDown.divide(benchReturnOnBenchDown, 4, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
