package com.financial.copilot.math.stock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * <h1>股票资产专有量化数学计算工具 (Stock Price Math Utils)</h1>
 * <p>
 * 职责：专为股票资产大类定制的高精度量化数学计算库，涵盖：
 * 1. 股票 Beta 系数（相对基准指数的敏感度/系统性风险）
 * 2. 价格区间动量 (Momentum)
 * 3. 换手率加权均价 (VWAP)
 * 4. 股票历史对数年化波动率
 * 5. RSI 相对强弱指标
 * 6. MACD 指数平滑异同移动平均线
 * 7. SMA / EMA 移动平均线
 * 遵循 Tool-as-Truth 规范，为 Stock 领域提供无幻觉的确定性计算支撑。
 * </p>
 *
 * @author FinancialCopilot
 */
public final class StockPriceMathUtils {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** 年化交易日基准 (250 天) */
    private static final BigDecimal TRADING_DAYS_PER_YEAR = new BigDecimal("250");

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

    // ── 技术指标计算 ──────────────────────────────────────────────

    /**
     * 计算简单移动平均线 (Simple Moving Average, SMA)
     * <p>
     * 公式: $SMA_n = \frac{1}{n} \sum_{i=1}^{n} P_i$
     * </p>
     *
     * @param prices 价格时序 (按时间正序排列)
     * @param period 移动平均周期 (如 5, 10, 20, 60)
     * @return SMA 值 (保留 2 位小数)，数据不足时返回 null
     */
    public static BigDecimal calculateSMA(List<BigDecimal> prices, int period) {
        if (prices == null || prices.size() < period || period <= 0) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        int start = prices.size() - period;
        for (int i = start; i < prices.size(); i++) {
            BigDecimal p = prices.get(i);
            if (p != null) {
                sum = sum.add(p);
            }
        }
        return sum.divide(BigDecimal.valueOf(period), 2, RoundingMode.HALF_UP);
    }

    /**
     * 计算指数移动平均线 (Exponential Moving Average, EMA)
     * <p>
     * 公式: $EMA_t = P_t \times k + EMA_{t-1} \times (1-k)$，其中 $k = \frac{2}{n+1}$
     * </p>
     *
     * @param prices 价格时序 (按时间正序排列)
     * @param period EMA 周期 (如 12, 26)
     * @return EMA 值 (保留 2 位小数)，数据不足时返回 null
     */
    public static BigDecimal calculateEMA(List<BigDecimal> prices, int period) {
        if (prices == null || prices.size() < period || period <= 0) {
            return null;
        }
        double k = 2.0 / (period + 1);
        // 以第一个周期的 SMA 作为初始 EMA
        double ema = 0.0;
        for (int i = 0; i < period; i++) {
            ema += prices.get(i) != null ? prices.get(i).doubleValue() : 0.0;
        }
        ema /= period;
        // 从第 period+1 个数据开始递推
        for (int i = period; i < prices.size(); i++) {
            double p = prices.get(i) != null ? prices.get(i).doubleValue() : 0.0;
            ema = p * k + ema * (1 - k);
        }
        return BigDecimal.valueOf(ema).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算相对强弱指标 (Relative Strength Index, RSI)
     * <p>
     * 公式: $RSI = 100 - \frac{100}{1 + RS}$，其中 $RS = \frac{平均上涨幅度}{平均下跌幅度}$
     * 采用 Wilder 平滑法 (指数移动平均)。
     * </p>
     *
     * @param prices 价格时序 (按时间正序排列，至少需要 period+1 个数据点)
     * @param period RSI 周期 (通常 6, 12, 14, 24)
     * @return RSI 值 (0~100，保留 2 位小数)，数据不足时返回 null
     */
    public static BigDecimal calculateRSI(List<BigDecimal> prices, int period) {
        if (prices == null || prices.size() < period + 1 || period <= 0) {
            return null;
        }
        // 计算日涨跌幅序列
        List<Double> changes = new ArrayList<>();
        for (int i = 1; i < prices.size(); i++) {
            BigDecimal prev = prices.get(i - 1);
            BigDecimal curr = prices.get(i);
            if (prev != null && curr != null) {
                changes.add(curr.subtract(prev).doubleValue());
            } else {
                changes.add(0.0);
            }
        }
        // 初始平均涨幅/跌幅 (简单平均)
        double avgGain = 0.0;
        double avgLoss = 0.0;
        for (int i = 0; i < period; i++) {
            double change = changes.get(i);
            if (change > 0) {
                avgGain += change;
            } else {
                avgLoss += Math.abs(change);
            }
        }
        avgGain /= period;
        avgLoss /= period;
        // Wilder 平滑递推
        for (int i = period; i < changes.size(); i++) {
            double change = changes.get(i);
            double gain = change > 0 ? change : 0.0;
            double loss = change < 0 ? Math.abs(change) : 0.0;
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }
        if (avgLoss == 0.0) {
            return new BigDecimal("100.00");
        }
        double rs = avgGain / avgLoss;
        double rsi = 100.0 - (100.0 / (1.0 + rs));
        return BigDecimal.valueOf(rsi).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算 MACD 指标 (Moving Average Convergence Divergence)
     * <p>
     * 标准参数: 快线 EMA(12), 慢线 EMA(26), 信号线 EMA(9)
     * MACD柱 = DIF - DEA
     * </p>
     *
     * @param prices    价格时序 (按时间正序排列，至少需要 35 个数据点)
     * @param fastPeriod 快线周期 (默认 12)
     * @param slowPeriod 慢线周期 (默认 26)
     * @param signalPeriod 信号线周期 (默认 9)
     * @return double[3]: [0]=DIF, [1]=DEA, [2]=MACD柱 (各保留 4 位小数)，数据不足时返回 null
     */
    public static double[] calculateMACD(List<BigDecimal> prices, int fastPeriod, int slowPeriod, int signalPeriod) {
        if (prices == null || prices.size() < slowPeriod + signalPeriod) {
            return null;
        }
        // 计算快线 EMA 序列
        double fastK = 2.0 / (fastPeriod + 1);
        double[] fastEma = new double[prices.size()];
        double sum = 0.0;
        for (int i = 0; i < fastPeriod; i++) {
            sum += prices.get(i) != null ? prices.get(i).doubleValue() : 0.0;
        }
        fastEma[fastPeriod - 1] = sum / fastPeriod;
        for (int i = fastPeriod; i < prices.size(); i++) {
            double p = prices.get(i) != null ? prices.get(i).doubleValue() : 0.0;
            fastEma[i] = p * fastK + fastEma[i - 1] * (1 - fastK);
        }
        // 计算慢线 EMA 序列
        double slowK = 2.0 / (slowPeriod + 1);
        double[] slowEma = new double[prices.size()];
        sum = 0.0;
        for (int i = 0; i < slowPeriod; i++) {
            sum += prices.get(i) != null ? prices.get(i).doubleValue() : 0.0;
        }
        slowEma[slowPeriod - 1] = sum / slowPeriod;
        for (int i = slowPeriod; i < prices.size(); i++) {
            double p = prices.get(i) != null ? prices.get(i).doubleValue() : 0.0;
            slowEma[i] = p * slowK + slowEma[i - 1] * (1 - slowK);
        }
        // DIF = 快线 EMA - 慢线 EMA (从 slowPeriod-1 开始有效)
        int difStart = slowPeriod - 1;
        int difLen = prices.size() - difStart;
        double[] dif = new double[difLen];
        for (int i = 0; i < difLen; i++) {
            dif[i] = fastEma[difStart + i] - slowEma[difStart + i];
        }
        // DEA = DIF 的 EMA(signalPeriod)
        if (dif.length < signalPeriod) {
            return null;
        }
        double signalK = 2.0 / (signalPeriod + 1);
        sum = 0.0;
        for (int i = 0; i < signalPeriod; i++) {
            sum += dif[i];
        }
        double dea = sum / signalPeriod;
        for (int i = signalPeriod; i < dif.length; i++) {
            dea = dif[i] * signalK + dea * (1 - signalK);
        }
        double difValue = dif[dif.length - 1];
        double macdBar = 2 * (difValue - dea);
        return new double[]{
            BigDecimal.valueOf(difValue).setScale(4, RoundingMode.HALF_UP).doubleValue(),
            BigDecimal.valueOf(dea).setScale(4, RoundingMode.HALF_UP).doubleValue(),
            BigDecimal.valueOf(macdBar).setScale(4, RoundingMode.HALF_UP).doubleValue()
        };
    }

    /**
     * 计算历史年化波动率 (Annualized Volatility)
     * <p>
     * 算法：基于对数日收益率的样本标准差 $\sigma_d$，年化波动率 $= \sigma_d \times \sqrt{250}$
     * </p>
     *
     * @param prices 价格时序 (按时间正序排列，至少需要 2 个数据点)
     * @return 年化波动率百分比 (如 25.50 表示 25.50%，保留 2 位小数)，数据不足时返回 null
     */
    public static BigDecimal calculateAnnualizedVolatility(List<BigDecimal> prices) {
        if (prices == null || prices.size() < 3) {
            return null;
        }
        // 计算对数日收益率
        List<Double> logReturns = new ArrayList<>();
        for (int i = 1; i < prices.size(); i++) {
            BigDecimal prev = prices.get(i - 1);
            BigDecimal curr = prices.get(i);
            if (prev != null && curr != null && prev.compareTo(BigDecimal.ZERO) > 0 && curr.compareTo(BigDecimal.ZERO) > 0) {
                logReturns.add(Math.log(curr.doubleValue() / prev.doubleValue()));
            }
        }
        if (logReturns.size() < 2) {
            return null;
        }
        int n = logReturns.size();
        double sum = 0.0;
        for (double r : logReturns) {
            sum += r;
        }
        double mean = sum / n;
        double varianceSum = 0.0;
        for (double r : logReturns) {
            double diff = r - mean;
            varianceSum += diff * diff;
        }
        double sampleStdDev = Math.sqrt(varianceSum / (n - 1));
        double annualizedVol = sampleStdDev * Math.sqrt(250.0) * 100.0;
        return BigDecimal.valueOf(annualizedVol).setScale(2, RoundingMode.HALF_UP);
    }
}
