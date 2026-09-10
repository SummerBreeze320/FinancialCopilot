package com.financial.copilot.math.stock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * <h1>股票资产专有量化数学计算工具 (Stock Price Math Utils)</h1>
 * <p>
 * 职责：专为股票资产大类定制的高精度量化数学计算库，涵盖：
 * 1. 股票 Beta 系数（相对基准指数的敏感度/系统性风险）
 * 2. 价格区间动量 (Momentum)
 * 3. 换手率加权均价 (VWAP)
 * 4. 股票历史对数年化波动率
 * 遵循 Tool-as-Truth 规范，为 Stock 领域提供无幻觉的确定性计算支撑。
 * </p>
 *
 * @author FinancialCopilot
 */
public final class StockPriceMathUtils {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private StockPriceMathUtils() {
        // 私有构造防实例化
    }

    /**
     * 计算个股相对基准指数的 Beta 系数 (系统性风险测度)
     * <p>
     * 公式: $\beta = \frac{\text{Cov}(R_s, R_m)}{\text{Var}(R_m)}$
     * </p>
     *
     * @param stockReturns  个股每日收益率序列 (%)
     * @param marketReturns 基准大盘每日收益率序列 (%)
     * @return Beta 系数（保留 2 位小数，1.0 表示与大盘波动一致）
     */
    public static BigDecimal calculateBeta(List<BigDecimal> stockReturns, List<BigDecimal> marketReturns) {
        if (stockReturns == null || marketReturns == null
                || stockReturns.isEmpty() || stockReturns.size() != marketReturns.size()) {
            return BigDecimal.ONE;
        }

        int n = stockReturns.size();
        if (n < 2) {
            return BigDecimal.ONE;
        }

        double stockSum = 0.0;
        double marketSum = 0.0;
        for (int i = 0; i < n; i++) {
            stockSum += stockReturns.get(i) != null ? stockReturns.get(i).doubleValue() : 0.0;
            marketSum += marketReturns.get(i) != null ? marketReturns.get(i).doubleValue() : 0.0;
        }
        double stockMean = stockSum / n;
        double marketMean = marketSum / n;

        double cov = 0.0;
        double marketVar = 0.0;
        for (int i = 0; i < n; i++) {
            double sDiff = (stockReturns.get(i) != null ? stockReturns.get(i).doubleValue() : 0.0) - stockMean;
            double mDiff = (marketReturns.get(i) != null ? marketReturns.get(i).doubleValue() : 0.0) - marketMean;
            cov += sDiff * mDiff;
            marketVar += mDiff * mDiff;
        }

        if (marketVar == 0.0) {
            return BigDecimal.ONE;
        }

        double beta = cov / marketVar;
        return BigDecimal.valueOf(beta).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算股票区间价格动量 (Price Momentum)
     * <p>
     * 公式: $\frac{P_{\text{end}} - P_{\text{start}}}{P_{\text{start}}} \times 100$
     * </p>
     *
     * @param startPrice 区间期初复权股价
     * @param endPrice   区间期末复权股价
     * @return 动量收益率百分比 (%)
     */
    public static BigDecimal calculatePriceMomentum(BigDecimal startPrice, BigDecimal endPrice) {
        if (startPrice == null || endPrice == null || startPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return endPrice.subtract(startPrice)
                .divide(startPrice, 4, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算日内成交量加权平均价 (Volume Weighted Average Price, VWAP)
     * <p>
     * 公式: $\text{VWAP} = \frac{\sum (P_i \times V_i)}{\sum V_i}$
     * </p>
     *
     * @param prices  成交分笔价格列表
     * @param volumes 成交分笔量列表
     * @return VWAP 价格
     */
    public static BigDecimal calculateVWAP(List<BigDecimal> prices, List<BigDecimal> volumes) {
        if (prices == null || volumes == null || prices.isEmpty() || prices.size() != volumes.size()) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalVolume = BigDecimal.ZERO;

        for (int i = 0; i < prices.size(); i++) {
            BigDecimal p = prices.get(i);
            BigDecimal v = volumes.get(i);
            if (p != null && v != null && v.compareTo(BigDecimal.ZERO) > 0) {
                totalValue = totalValue.add(p.multiply(v));
                totalVolume = totalVolume.add(v);
            }
        }

        if (totalVolume.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        return totalValue.divide(totalVolume, 2, RoundingMode.HALF_UP);
    }
}
