package com.financial.copilot.domain.fund.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <h1>公募基金基础信息领域实体</h1>
 * <p>
 * 表示一只在基金行业登记注册的基金产品标的，涵盖代码、全称、分类、成立时间、在管规模等核心基础事实。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundInfo {

    /** 6位基金代码 (例如: 005827) */
    private String fundCode;

    /** 基金全称 (例如: 易方达蓝筹精选混合型证券投资基金) */
    private String fundName;

    /** 基金类型 (例如: 股票型、偏股混合型、中长期纯债型等) */
    private String fundType;

    /** 基金成立日期 */
    private LocalDate establishmentDate;

    /** 基金管理人 (基金公司) ID 或全称 */
    private String managementCompanyId;

    /** 最新基金资产净值规模 (单位: 亿元) */
    private BigDecimal currentScaleBillion;

    /** 业绩比较基准说明 (Benchmark) */
    private String trackingBenchmark;

    /** 托管银行机构全称 */
    private String custodianBank;
}
