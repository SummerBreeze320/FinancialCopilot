package com.financial.copilot.common.fund.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <h1>公募基金全景量化指标数据传输对象</h1>
 * <p>
 * 严格遵从 "Tool-as-Truth" 原则，所有数值字段由纯 Java 原生量化计算引擎产出，
 * 供 AnalyzerAgent、ComparatorAgent 及 ReportSynthesizer 作为客观事实依据，杜绝大模型心算幻觉。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundMetricsDTO implements Serializable {

    /** 6位基金标准代码 (例如: 005827) */
    private String fundCode;

    /** 基金全称 (例如: 易方达蓝筹精选混合型证券投资基金) */
    private String fundName;

    /** 基金类型 (例如: 偏股混合型) */
    private String fundType;

    /** 统计区间起始日期 */
    private LocalDate startDate;

    /** 统计区间截止日期 */
    private LocalDate endDate;

    /** 区间累计净值增长率 (%)，公式: (期末复权净值 - 期初复权净值) / 期初复权净值 * 100 */
    private BigDecimal cumulativeReturn;

    /** 几何年化收益率 (%)，公式: (1 + 累计收益率)^(365 / 天数) - 1 */
    private BigDecimal annualizedReturn;

    /** 区间最大动态回撤率 (%)，公式: Max((历史最高峰值 - 随后波谷值) / 历史最高峰值) * 100 */
    private BigDecimal maxDrawdown;

    /** 年化波动率 (%)，基于日收益率样本标准差经根号 250 交易日折算 */
    private BigDecimal annualizedVolatility;

    /** 夏普比率 (Sharpe Ratio)，衡量单位总风险的超额回报，无风险利率默认 2.5% */
    private BigDecimal sharpeRatio;

    /** 卡玛比率 (Calmar Ratio)，衡量单位极值回撤风险下的收益补偿，公式: 年化收益率 / 最大回撤 */
    private BigDecimal calmarRatio;

    /** 前十大重仓股持仓净值集中度 (%) */
    private BigDecimal top10Concentration;

    /** 第一大重仓行业板块 (申万一级行业) */
    private String primarySector;

    /** 第一大重仓行业占净值比例 (%) */
    private BigDecimal primarySectorRatio;
}
