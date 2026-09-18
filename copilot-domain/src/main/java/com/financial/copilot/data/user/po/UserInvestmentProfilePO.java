package com.financial.copilot.data.user.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;
import com.financial.copilot.domain.user.enums.InvestmentHorizon;
import com.financial.copilot.domain.user.enums.InvestmentStyle;
import com.financial.copilot.domain.user.enums.RiskToleranceLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <h1>用户投资画像与风险偏好持久化对象 (User Investment Profile PO)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user_investment_profile")
public class UserInvestmentProfilePO {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 所属系统用户 ID（主键）
     */
    @TableId(value = "user_id")
    private Long userId;

    /**
     * 投资风险承受能力评级 (C1 保守型 ~ C5 进取型)
     */
    private String riskToleranceLevel;

    /**
     * 计划投资期限（SHORT_TERM &lt; 1年, MEDIUM_TERM 1~3年, LONG_TERM &gt; 3年）
     */
    private String investmentHorizon;

    /**
     * 偏好的大类资产（JSON 数组字符串，如 ["EQUITY", "BOND"]）
     */
    private String preferredAssetClasses;

    /**
     * 偏好的投资板块/行业主题（JSON 数组字符串，如 ["半导体", "创新药"]）
     */
    private String preferredSectors;

    /**
     * 最大可承受回撤比例（例如 0.20 代表 20%）
     */
    private BigDecimal maxDrawdownTolerance;

    /**
     * 目标年化收益率期望（例如 0.15 代表 15%）
     */
    private BigDecimal targetAnnualReturn;

    /**
     * 投资风格偏好（VALUE 价值型, GROWTH 成长型, BALANCED 平衡型）
     */
    private String investmentStyle;

    /**
     * 单只标的最高仓位上限限制（例如 0.25 代表单基金/股票不超 25%）
     */
    private BigDecimal singlePositionLimit;

    /**
     * 投资画像最后更新时间戳
     */
    private LocalDateTime updatedAt;

    public UserInvestmentProfile toDomain() {
        List<String> assetClasses = new ArrayList<>();
        List<String> sectors = new ArrayList<>();

        try {
            if (preferredAssetClasses != null && !preferredAssetClasses.isBlank()) {
                assetClasses = OBJECT_MAPPER.readValue(preferredAssetClasses, new TypeReference<>() {});
            }
            if (preferredSectors != null && !preferredSectors.isBlank()) {
                sectors = OBJECT_MAPPER.readValue(preferredSectors, new TypeReference<>() {});
            }
        } catch (Exception ignored) {
        }

        return UserInvestmentProfile.builder()
                .userId(userId)
                .riskToleranceLevel(RiskToleranceLevel.fromCode(riskToleranceLevel))
                .investmentHorizon(investmentHorizon != null ? InvestmentHorizon.valueOf(investmentHorizon) : InvestmentHorizon.MEDIUM_TERM)
                .preferredAssetClasses(assetClasses)
                .preferredSectors(sectors)
                .maxDrawdownTolerance(maxDrawdownTolerance != null ? maxDrawdownTolerance : new BigDecimal("15.00"))
                .targetAnnualReturn(targetAnnualReturn != null ? targetAnnualReturn : new BigDecimal("12.00"))
                .investmentStyle(investmentStyle != null ? InvestmentStyle.valueOf(investmentStyle) : InvestmentStyle.BALANCED)
                .singlePositionLimit(singlePositionLimit != null ? singlePositionLimit : new BigDecimal("20.00"))
                .updatedAt(updatedAt)
                .build();
    }

    public static UserInvestmentProfilePO fromDomain(UserInvestmentProfile domain) {
        if (domain == null) return null;

        String assetJson = "[]";
        String sectorJson = "[]";
        try {
            if (domain.getPreferredAssetClasses() != null) {
                assetJson = OBJECT_MAPPER.writeValueAsString(domain.getPreferredAssetClasses());
            }
            if (domain.getPreferredSectors() != null) {
                sectorJson = OBJECT_MAPPER.writeValueAsString(domain.getPreferredSectors());
            }
        } catch (Exception ignored) {
        }

        return UserInvestmentProfilePO.builder()
                .userId(domain.getUserId())
                .riskToleranceLevel(domain.getRiskToleranceLevel() != null ? domain.getRiskToleranceLevel().getCode() : "C3")
                .investmentHorizon(domain.getInvestmentHorizon() != null ? domain.getInvestmentHorizon().name() : InvestmentHorizon.MEDIUM_TERM.name())
                .preferredAssetClasses(assetJson)
                .preferredSectors(sectorJson)
                .maxDrawdownTolerance(domain.getMaxDrawdownTolerance())
                .targetAnnualReturn(domain.getTargetAnnualReturn())
                .investmentStyle(domain.getInvestmentStyle() != null ? domain.getInvestmentStyle().name() : InvestmentStyle.BALANCED.name())
                .singlePositionLimit(domain.getSinglePositionLimit())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
