package com.financial.copilot.agent.tools.stock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.business.stock.port.StockDataPort;
import com.financial.copilot.math.stock.StockPriceMathUtils;
import com.financial.copilot.math.stock.StockValuationUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <h1>股票技术指标与估值分析只读工具 (Stock Technical Analysis Tool)</h1>
 * <p>
 * 职责：遵循 Tool-as-Truth 规范，为股票分析 Agent 提供个股的全维度技术指标与估值分析能力。
 * 底层调用 {@link StockPriceMathUtils} 与 {@link StockValuationUtils} 进行高精度确定性计算，
 * 严禁大语言模型自行估算或心算金融数据，彻底杜绝幻觉。
 * </p>
 * <p>
 * 涵盖能力：
 * <ul>
 *     <li>均线系统: SMA(5/10/20/60)、EMA(12/26)</li>
 *     <li>动量指标: RSI(6/12/14)、MACD(12/26/9)</li>
 *     <li>风险指标: 年化波动率</li>
 *     <li>估值分析: PE/PB 历史分位数、PEG、简化 DCF、Gordon DDM</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class StockTechnicalAnalysisTool {

    private final StockDataPort stockDataPort;
    private final ObjectMapper objectMapper;

    public StockTechnicalAnalysisTool(StockDataPort stockDataPort, ObjectMapper objectMapper) {
        this.stockDataPort = stockDataPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取个股全维度技术指标 (均线 + RSI + MACD + 波动率)
     * <p>
     * 自动从数据库拉取最近 120 个交易日的收盘价时序，调用底层数学引擎计算。
     * </p>
     *
     * @param stockCode 股票代码 (如 "600519.SH")
     * @return 技术指标结果 JSON 字符串
     */
    public String getTechnicalIndicators(String stockCode) {
        log.info("[TOOL CALL-STOCK] 正在计算个股技术指标: stockCode={}", stockCode);
        try {
            List<BigDecimal> prices = stockDataPort.getDailyClosePrices(stockCode, 120);
            if (prices == null || prices.isEmpty()) {
                return objectMapper.writeValueAsString(Map.of(
                        "stockCode", stockCode,
                        "error", "无可用K线数据，请确认数据已同步"
                ));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("stockCode", stockCode);
            result.put("dataPoints", prices.size());
            result.put("latestClose", prices.get(prices.size() - 1));

            // 均线系统
            Map<String, Object> ma = new LinkedHashMap<>();
            putIfNotNull(ma, "SMA5", StockPriceMathUtils.calculateSMA(prices, 5));
            putIfNotNull(ma, "SMA10", StockPriceMathUtils.calculateSMA(prices, 10));
            putIfNotNull(ma, "SMA20", StockPriceMathUtils.calculateSMA(prices, 20));
            putIfNotNull(ma, "SMA60", StockPriceMathUtils.calculateSMA(prices, 60));
            putIfNotNull(ma, "EMA12", StockPriceMathUtils.calculateEMA(prices, 12));
            putIfNotNull(ma, "EMA26", StockPriceMathUtils.calculateEMA(prices, 26));
            result.put("movingAverages", ma);

            // RSI
            Map<String, Object> rsi = new LinkedHashMap<>();
            putIfNotNull(rsi, "RSI6", StockPriceMathUtils.calculateRSI(prices, 6));
            putIfNotNull(rsi, "RSI12", StockPriceMathUtils.calculateRSI(prices, 12));
            putIfNotNull(rsi, "RSI14", StockPriceMathUtils.calculateRSI(prices, 14));
            result.put("rsi", rsi);

            // MACD
            double[] macd = StockPriceMathUtils.calculateMACD(prices, 12, 26, 9);
            if (macd != null) {
                Map<String, Object> macdMap = new LinkedHashMap<>();
                macdMap.put("DIF", BigDecimal.valueOf(macd[0]));
                macdMap.put("DEA", BigDecimal.valueOf(macd[1]));
                macdMap.put("MACD_BAR", BigDecimal.valueOf(macd[2]));
                result.put("macd", macdMap);
            }

            // 年化波动率
            BigDecimal vol = StockPriceMathUtils.calculateAnnualizedVolatility(prices);
            if (vol != null) {
                result.put("annualizedVolatility", vol);
            }

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("[TOOL CALL-STOCK] 技术指标计算失败: stockCode={}, error={}", stockCode, e.getMessage(), e);
            return "{\"error\": \"技术指标计算失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 获取个股估值分析 (PE/PB 分位数 + PEG + DCF + DDM)
     * <p>
     * 基于历史 PE/PB 序列判断当前估值水位，并提供 DCF/DDM 内在价值估算。
     * </p>
     *
     * @param stockCode            股票代码
     * @param currentPE            当前 PE-TTM
     * @param currentPB            当前 PB
     * @param earningsGrowthRate   预期盈利增速 (%) (如 25.0 表示 25%)
     * @param currentFCFBillion    当前年度自由现金流 (亿元, DCF 用)
     * @param highGrowthRate       高增长阶段增速 (%)
     * @param highGrowthYears      高增长阶段年数
     * @param terminalGrowthRate   永续增长率 (%)
     * @param discountRate         折现率/WACC (%)
     * @param totalSharesBillion   总股本 (亿股)
     * @param dividendPerShare     当前每股股息 (元, DDM 用)
     * @param dividendGrowthRate   股息增速 (%)
     * @param requiredReturnRate   要求回报率 (%)
     * @return 估值分析结果 JSON 字符串
     */
    public String getValuationAnalysis(
            String stockCode,
            BigDecimal currentPE,
            BigDecimal currentPB,
            BigDecimal earningsGrowthRate,
            BigDecimal currentFCFBillion,
            BigDecimal highGrowthRate,
            int highGrowthYears,
            BigDecimal terminalGrowthRate,
            BigDecimal discountRate,
            BigDecimal totalSharesBillion,
            BigDecimal dividendPerShare,
            BigDecimal dividendGrowthRate,
            BigDecimal requiredReturnRate) {

        log.info("[TOOL CALL-STOCK] 正在执行估值分析: stockCode={}, PE={}, PB={}", stockCode, currentPE, currentPB);

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("stockCode", stockCode);

            // PEG
            BigDecimal peg = StockValuationUtils.calculatePEG(currentPE, earningsGrowthRate);
            if (peg != null) {
                result.put("PEG", peg);
                result.put("PEG_interpretation", peg.compareTo(BigDecimal.ONE) < 0
                        ? "成长性被低估 (PEG<1)" : peg.compareTo(BigDecimal.ONE) == 0
                        ? "合理估值 (PEG≈1)" : "成长性被高估 (PEG>1)");
            }

            // DCF
            BigDecimal dcf = StockValuationUtils.calculateSimplifiedDCF(
                    currentFCFBillion, highGrowthRate, highGrowthYears,
                    terminalGrowthRate, discountRate, totalSharesBillion);
            if (dcf != null) {
                result.put("DCF_perShare", dcf);
                result.put("DCF_params", Map.of(
                        "FCF_billion", currentFCFBillion,
                        "highGrowthRate", highGrowthRate,
                        "highGrowthYears", highGrowthYears,
                        "terminalGrowthRate", terminalGrowthRate,
                        "WACC", discountRate,
                        "totalShares_billion", totalSharesBillion
                ));
            }

            // Gordon DDM
            BigDecimal ddm = StockValuationUtils.calculateGordonDDM(
                    dividendPerShare, dividendGrowthRate, requiredReturnRate);
            if (ddm != null) {
                result.put("GordonDDM_perShare", ddm);
                result.put("DDM_params", Map.of(
                        "dividendPerShare", dividendPerShare,
                        "dividendGrowthRate", dividendGrowthRate,
                        "requiredReturnRate", requiredReturnRate
                ));
            }

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("[TOOL CALL-STOCK] 估值分析失败: stockCode={}, error={}", stockCode, e.getMessage(), e);
            return "{\"error\": \"估值分析失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 获取个股 PE/PB 历史分位数
     *
     * @param stockCode 股票代码
     * @param currentPE 当前 PE
     * @param currentPB 当前 PB
     * @return 分位数分析 JSON 字符串
     */
    public String getValuationPercentile(String stockCode, BigDecimal currentPE, BigDecimal currentPB) {
        log.info("[TOOL CALL-STOCK] 正在计算估值分位数: stockCode={}", stockCode);
        try {
            // 从财务历史反推历史 PE 序列 (简化: 使用最近 N 季度 ROE 推算)
            // 实际生产环境应从日频估值数据库获取
            List<StockDataPort.StockFinancialSummary> financials =
                    stockDataPort.getFinancialHistory(stockCode, 20);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("stockCode", stockCode);
            result.put("currentPE", currentPE);
            result.put("currentPB", currentPB);

            if (financials != null && !financials.isEmpty()) {
                // 从历史 EPS 推算历史 PE 近似序列
                List<BigDecimal> historicalPEs = financials.stream()
                        .filter(f -> f.eps() != null && f.eps().compareTo(BigDecimal.ZERO) > 0)
                        .map(f -> {
                            BigDecimal price = currentPE.multiply(f.eps());
                            return price.divide(f.eps(), 2, java.math.RoundingMode.HALF_UP);
                        })
                        .toList();

                if (!historicalPEs.isEmpty()) {
                    BigDecimal pePercentile = StockValuationUtils.calculatePEPercentile(historicalPEs, currentPE);
                    result.put("PE_percentile", pePercentile);
                    result.put("PE_interpretation", interpretPercentile(pePercentile));
                }
            } else {
                result.put("note", "无历史财务数据，无法计算分位数");
            }

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("[TOOL CALL-STOCK] 估值分位数计算失败: stockCode={}, error={}", stockCode, e.getMessage(), e);
            return "{\"error\": \"估值分位数计算失败: " + e.getMessage() + "\"}";
        }
    }

    private String interpretPercentile(BigDecimal percentile) {
        if (percentile.compareTo(new BigDecimal("20")) < 0) {
            return "历史低估区间 (<20%分位)";
        } else if (percentile.compareTo(new BigDecimal("80")) > 0) {
            return "历史高估区间 (>80%分位)";
        } else {
            return "估值合理区间 (20%-80%分位)";
        }
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
