package com.financial.copilot.math.stock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;

/**
 * <h1>股票估值分析数学计算工具 (Stock Valuation Utils)</h1>
 * <p>
 * 职责：遵循 Tool-as-Truth 规范，为个股估值分析提供高精度确定性计算，涵盖：
 * 1. PE / PB 历史分位数 (估值水位判断)
 * 2. 简化 DCF 自由现金流折现模型
 * 3. PEG 估值比率
 * 遵循严格 BigDecimal 精度控制，杜绝大模型心算幻觉。
 * </p>
 *
 * @author FinancialCopilot
 */
public final class StockValuationUtils {

    private StockValuationUtils() {
        // 私有构造防实例化
    }

    /**
     * 计算当前 PE 在历史 PE 序列中的分位数 (百分位排名)
     * <p>
     * 用于判断个股当前估值处于历史什么水位：
     * - 分位数 &lt; 20%: 历史低估区间
     * - 分位数 20%~80%: 合理区间
     * - 分位数 &gt; 80%: 历史高估区间
     * </p>
     *
     * @param historicalPEs 历史 PE 序列 (如近 5 年所有交易日的 PE-TTM)
     * @param currentPE     当前 PE
     * @return 分位数百分比 (0~100，保留 2 位小数)，如 35.20 表示处于历史 35.20% 分位
     */
    public static BigDecimal calculatePEPercentile(List<BigDecimal> historicalPEs, BigDecimal currentPE) {
        return calculatePercentile(historicalPEs, currentPE);
    }

    /**
     * 计算当前 PB 在历史 PB 序列中的分位数
     *
     * @param historicalPBs 历史 PB 序列
     * @param currentPB     当前 PB
     * @return 分位数百分比 (0~100，保留 2 位小数)
     */
    public static BigDecimal calculatePBPercentile(List<BigDecimal> historicalPBs, BigDecimal currentPB) {
        return calculatePercentile(historicalPBs, currentPB);
    }

    /**
     * 通用分位数计算 (百分位排名)
     * <p>
     * 公式: $Percentile = \frac{count(value_i \leq current)}{N} \times 100$
     * </p>
     *
     * @param historicalValues 历史值序列
     * @param currentValue     当前值
     * @return 分位数百分比 (0~100)
     */
    private static BigDecimal calculatePercentile(List<BigDecimal> historicalValues, BigDecimal currentValue) {
        if (historicalValues == null || historicalValues.isEmpty() || currentValue == null) {
            return BigDecimal.ZERO;
        }
        // 过滤掉 null 和非正值
        List<BigDecimal> valid = historicalValues.stream()
                .filter(v -> v != null && v.compareTo(BigDecimal.ZERO) > 0)
                .sorted()
                .toList();
        if (valid.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int count = 0;
        for (BigDecimal v : valid) {
            if (v.compareTo(currentValue) <= 0) {
                count++;
            }
        }
        double percentile = (double) count / valid.size() * 100.0;
        return BigDecimal.valueOf(percentile).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算 PEG 比率 (Price/Earnings-to-Growth)
     * <p>
     * 公式: $PEG = \frac{PE}{G}$
     * 其中 G 为未来预期盈利增速 (%)
     * - PEG &lt; 1: 成长性被低估
     * - PEG = 1: 合理估值
     * - PEG &gt; 1: 成长性被高估
     * </p>
     *
     * @param peRatio           当前市盈率 (PE-TTM)
     * @param earningsGrowthRate 预期盈利增速百分比 (如 25.0 表示 25%)
     * @return PEG 比率 (保留 2 位小数)，增速为 0 或 null 时返回 null
     */
    public static BigDecimal calculatePEG(BigDecimal peRatio, BigDecimal earningsGrowthRate) {
        if (peRatio == null || earningsGrowthRate == null
                || earningsGrowthRate.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return peRatio.divide(earningsGrowthRate, 2, RoundingMode.HALF_UP);
    }

    /**
     * 简化 DCF 自由现金流折现模型 (两阶段模型)
     * <p>
     * 第一阶段 (高增长期): $FCF_t = FCF_0 \times (1+g_1)^t$
     * 第二阶段 (永续增长): $TV = \frac{FCF_n \times (1+g_2)}{r - g_2}$
     * 内在价值 = $\sum \frac{FCF_t}{(1+r)^t} + \frac{TV}{(1+r)^n}$
     * </p>
     *
     * @param currentFCFBillion   当前年度自由现金流 (亿元)
     * @param highGrowthRate      高增长阶段增速 (如 15.0 表示 15%)
     * @param highGrowthYears     高增长阶段年数 (如 5)
     * @param terminalGrowthRate  永续增长率 (如 3.0 表示 3%)
     * @param discountRate        折现率/WACC (如 10.0 表示 10%)
     * @param totalSharesBillion  总股本 (亿股)
     * @return 每股内在价值 (元，保留 2 位小数)，参数异常时返回 null
     */
    public static BigDecimal calculateSimplifiedDCF(BigDecimal currentFCFBillion,
                                                     BigDecimal highGrowthRate,
                                                     int highGrowthYears,
                                                     BigDecimal terminalGrowthRate,
                                                     BigDecimal discountRate,
                                                     BigDecimal totalSharesBillion) {
        if (currentFCFBillion == null || currentFCFBillion.compareTo(BigDecimal.ZERO) <= 0
                || highGrowthRate == null || terminalGrowthRate == null || discountRate == null
                || totalSharesBillion == null || totalSharesBillion.compareTo(BigDecimal.ZERO) <= 0
                || highGrowthYears <= 0
                || discountRate.compareTo(terminalGrowthRate) <= 0) {
            return null;
        }

        double fcf = currentFCFBillion.doubleValue();
        double g1 = highGrowthRate.doubleValue() / 100.0;
        double g2 = terminalGrowthRate.doubleValue() / 100.0;
        double r = discountRate.doubleValue() / 100.0;
        double shares = totalSharesBillion.doubleValue();

        // 第一阶段: 高增长期 FCF 折现
        double pvFCF = 0.0;
        double fcfT = fcf;
        for (int t = 1; t <= highGrowthYears; t++) {
            fcfT = fcfT * (1 + g1);
            pvFCF += fcfT / Math.pow(1 + r, t);
        }

        // 第二阶段: 终值折现
        double terminalFCF = fcfT * (1 + g2);
        double terminalValue = terminalFCF / (r - g2);
        double pvTerminal = terminalValue / Math.pow(1 + r, highGrowthYears);

        // 总内在价值 = 第一阶段 + 终值折现
        double totalValue = pvFCF + pvTerminal;
        double perShareValue = totalValue / shares;

        return BigDecimal.valueOf(perShareValue).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算股息折现模型 (Dividend Discount Model, Gordon 模型)
     * <p>
     * 公式: $V = \frac{D_1}{r - g}$
     * 其中 $D_1 = D_0 \times (1+g)$
     * </p>
     *
     * @param currentDividendPerShare 当前每股股息 (元)
     * @param dividendGrowthRate      股息增速百分比 (如 5.0 表示 5%)
     * @param requiredReturnRate      要求回报率百分比 (如 10.0 表示 10%)
     * @return 每股内在价值 (元，保留 2 位小数)，参数异常时返回 null
     */
    public static BigDecimal calculateGordonDDM(BigDecimal currentDividendPerShare,
                                                 BigDecimal dividendGrowthRate,
                                                 BigDecimal requiredReturnRate) {
        if (currentDividendPerShare == null || dividendGrowthRate == null || requiredReturnRate == null
                || requiredReturnRate.compareTo(dividendGrowthRate) <= 0
                || currentDividendPerShare.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        double d0 = currentDividendPerShare.doubleValue();
        double g = dividendGrowthRate.doubleValue() / 100.0;
        double r = requiredReturnRate.doubleValue() / 100.0;
        double d1 = d0 * (1 + g);
        double value = d1 / (r - g);
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
