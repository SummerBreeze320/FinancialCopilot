package com.financial.copilot.domain.wealth.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <h1>银行理财产品领域实体 (Wealth Product Entity)</h1>
 * <p>
 * 职责：代表商业银行及银行理财子公司发行的净值型/固收型理财产品业务领域对象。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WealthProductInfo {

    /**
     * 理财产品登记编码（如全国银行业理财信息登记系统编码 "Z7000823000012"）
     */
    private String productCode;

    /**
     * 理财产品全称
     */
    private String productName;

    /**
     * 发行机构/理财子公司名称（如 "招银理财"、"中邮理财"）
     */
    private String issuerCompany;

    /**
     * 投资性质（固定收益类 / 混合类 / 权益类 / 商品及金融衍生品类）
     */
    private String investmentNature;

    /**
     * 风险评级（PR1 低风险 / PR2 中低风险 / PR3 中风险 / PR4 中高风险 / PR5 高风险）
     */
    private String riskLevel;

    /**
     * 业绩比较基准（如 "年化 3.20%-3.80%"）
     */
    private String benchmarkYield;

    /**
     * 起购金额（元）
     */
    private BigDecimal minSubscriptionAmount;

    /**
     * 产品成立日期
     */
    private LocalDate establishmentDate;
}
