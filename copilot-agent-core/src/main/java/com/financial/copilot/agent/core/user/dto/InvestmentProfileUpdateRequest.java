package com.financial.copilot.agent.core.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * <h1>用户投资画像与偏好设置更新请求传输对象 (Investment Persona Update Request)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestmentProfileUpdateRequest implements Serializable {

    /** 风险承受能力等级评级 (C1 ~ C5) */
    private String riskToleranceLevel;

    /** 投资期限偏好 (SHORT_TERM, MEDIUM_TERM, LONG_TERM) */
    private String investmentHorizon;

    /** 偏好关注的资产门类列表 (如 ["FUND", "STOCK"]) */
    private List<String> preferredAssetClasses;

    /** 偏好关注的行业/板块列表 (如 ["医药生物", "半导体芯片", "新能源"]) */
    private List<String> preferredSectors;

    /** 最大可承受回撤比例 (%) */
    private BigDecimal maxDrawdownTolerance;

    /** 目标预期年化收益率 (%) */
    private BigDecimal targetAnnualReturn;

    /** 偏好投资哲学与风格 (VALUE, GROWTH, BALANCED, DIVIDEND) */
    private String investmentStyle;

    /** 单标的最大持仓上限比例 (%) */
    private BigDecimal singlePositionLimit;
}
