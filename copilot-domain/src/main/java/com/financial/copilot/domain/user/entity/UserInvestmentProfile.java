package com.financial.copilot.domain.user.entity;

import com.financial.copilot.domain.user.enums.InvestmentHorizon;
import com.financial.copilot.domain.user.enums.InvestmentStyle;
import com.financial.copilot.domain.user.enums.RiskToleranceLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <h1>用户投资画像与风险偏好领域实体 (User Investment Profile / Persona)</h1>
 * <p>
 * 职责：构建投资者的数字画像（C1~C5 评级、回撤容忍度、目标收益率、板块偏好与策略风格）。
 * <b>关键作用</b>：作为客观事实上下文注入投研黑板 {@code ResearchBlackboard}，
 * 驱动首席投资官主编 Agent ({@code ReportSynthesizer}) 自动产出与用户资金属性深度匹配的适格资产配置方案（千人千面）。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInvestmentProfile implements Serializable {

    /** 关联的用户唯一 ID */
    private Long userId;

    /** 风险承受能力等级评级 (C1~C5) */
    @Builder.Default
    private RiskToleranceLevel riskToleranceLevel = RiskToleranceLevel.C3;

    /** 投资期限偏好 (短期/中期/长期) */
    @Builder.Default
    private InvestmentHorizon investmentHorizon = InvestmentHorizon.MEDIUM_TERM;

    /** 偏好关注的资产门类列表 (如 ["FUND", "STOCK"]) */
    @Builder.Default
    private List<String> preferredAssetClasses = new ArrayList<>();

    /** 偏好关注的行业/主题板块列表 (如 ["医药生物", "半导体芯片", "新能源", "大消费"]) */
    @Builder.Default
    private List<String> preferredSectors = new ArrayList<>();

    /** 最大可承受回撤比例 (如 15.00 表示 15%) */
    @Builder.Default
    private BigDecimal maxDrawdownTolerance = new BigDecimal("15.00");

    /** 预期目标年化收益率 (如 12.00 表示 12%) */
    @Builder.Default
    private BigDecimal targetAnnualReturn = new BigDecimal("12.00");

    /** 偏好投资风格与选股哲学 */
    @Builder.Default
    private InvestmentStyle investmentStyle = InvestmentStyle.BALANCED;

    /** 单只标的推荐最大持仓上限比例 (如 20.00 表示 20%) */
    @Builder.Default
    private BigDecimal singlePositionLimit = new BigDecimal("20.00");

    /** 最近评估与更新时间 */
    private LocalDateTime updatedAt;

    /**
     * 生成供大模型投研 Agent 理解的结构化自然语言画像摘要
     *
     * @return 格式化的投资者肖像描述
     */
    public String toAgentPromptSummary() {
        return String.format(
                "【投资者适格画像】: 风险评级=%s(%s), 投资期限=%s, 偏好风格=%s, 目标年化=%s%%, 最大承受回撤=%s%%, 偏好板块=%s, 单标的仓位上限=%s%%",
                riskToleranceLevel.getCode(),
                riskToleranceLevel.getDisplayName(),
                investmentHorizon.getDescription(),
                investmentStyle.name(),
                targetAnnualReturn != null ? targetAnnualReturn.toPlainString() : "不限",
                maxDrawdownTolerance != null ? maxDrawdownTolerance.toPlainString() : "不限",
                preferredSectors != null && !preferredSectors.isEmpty() ? String.join(",", preferredSectors) : "全行业覆盖",
                singlePositionLimit != null ? singlePositionLimit.toPlainString() : "20.00"
        );
    }
}
