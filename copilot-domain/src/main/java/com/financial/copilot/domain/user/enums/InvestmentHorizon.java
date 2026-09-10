package com.financial.copilot.domain.user.enums;

import lombok.Getter;

/**
 * <h1>投资期限偏好枚举 (Investment Horizon)</h1>
 * <p>
 * 决定投研报告中推荐标的的流动性要求与仓位持有久期建议：
 * <ul>
 *   <li>SHORT_TERM: 短期 (< 1年) - 强流动性、低波动、波段择时或货币理财</li>
 *   <li>MEDIUM_TERM: 中期 (1 - 3年) - 兼顾基本面周期、行业轮动与成长收益</li>
 *   <li>LONG_TERM: 长期 (> 3年) - 穿越牛熊周期、巴菲特式价值复利与底仓配置</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Getter
public enum InvestmentHorizon {

    SHORT_TERM("短期持有 (< 1年)"),
    MEDIUM_TERM("中期持有 (1 - 3年)"),
    LONG_TERM("长期持有 (> 3年)");

    private final String description;

    InvestmentHorizon(String description) {
        this.description = description;
    }
}
