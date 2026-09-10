package com.financial.copilot.domain.user.enums;

import lombok.Getter;

/**
 * <h1>投资风格与策略偏好枚举 (Investment Style)</h1>
 * <p>
 * 决定 Agent 选股与选基侧重于估值防守、成长弹性还是股息分红。
 * </p>
 *
 * @author FinancialCopilot
 */
@Getter
public enum InvestmentStyle {

    VALUE("深度价值型 - 强调低估值、高安全边际与破净修复"),
    GROWTH("成长驱动型 - 强调高营收增速、高ROE盈利爆发与新质生产力"),
    BALANCED("均衡配置型 - 兼顾行业分散与大盘蓝筹/中盘成长搭配"),
    DIVIDEND("红利低波型 - 强调稳定现金流分红、央国企高股息与低波动防守");

    private final String description;

    InvestmentStyle(String description) {
        this.description = description;
    }
}
