package com.financial.copilot.domain.fund.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * <h1>公募基金经理档案领域实体</h1>
 * <p>
 * 封装基金经理的人员背景、所属公募机构、累计从业天数、在管总资产规模及历史最高回报代表作等信息。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundManager {

    /** 基金经理唯一标识编码 */
    private String managerId;

    /** 基金经理真实姓名 (例如: 张坤、朱少醒、葛兰) */
    private String managerName;

    /** 所属基金管理公司 ID 或机构全称 */
    private String companyId;

    /** 性别 */
    private String gender;

    /** 最高学历 (如: 硕士、博士) */
    private String education;

    /** 证券与基金行业从业累计天数 */
    private Integer workingDays;

    /** 现任在管基金总资产规模合计 (单位: 亿元) */
    private BigDecimal currentTotalScaleBillion;

    /** 历任最佳代表作基金代码 */
    private String bestFundCode;

    /** 历任最佳代表作任职期累计回报率 (%) */
    private BigDecimal bestFundReturn;
}
