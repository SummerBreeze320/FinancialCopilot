package com.financial.copilot.domain.fund.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <h1>基金管理公司领域实体</h1>
 * <p>
 * 封装公募基金管理公司（例如易方达基金、富国基金、中欧基金等）的机构规模与投研梯队特征。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundCompany {

    /** 基金公司唯一标识编码 */
    private String companyId;

    /** 基金公司法定全称 (例如: 易方达基金管理有限公司) */
    private String companyName;

    /** 公司行业通用简称 (例如: 易方达基金) */
    private String shortName;

    /** 公司注册成立日期 */
    private LocalDate establishmentDate;

    /** 全部公募产品在管总规模 (单位: 亿元) */
    private BigDecimal totalScaleBillion;

    /** 权益类产品 (股票型 + 偏股混合型) 总在管规模 (单位: 亿元) */
    private BigDecimal equityScaleBillion;

    /** 旗下现任注册公募基金经理总人数 */
    private Integer managerCount;

    /** 旗下在管公募基金产品总只数 */
    private Integer fundCount;
}
