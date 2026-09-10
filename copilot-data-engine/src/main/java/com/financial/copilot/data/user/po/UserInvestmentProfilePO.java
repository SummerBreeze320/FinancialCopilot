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

    @TableId(value = "user_id")
    private Long userId;

    private String riskToleranceLevel;

    private String investmentHorizon;

    private String preferredAssetClasses;

    private String preferredSectors;

    private BigDecimal maxDrawdownTolerance;

    private BigDecimal targetAnnualReturn;

    private String investmentStyle;

    private BigDecimal singlePositionLimit;

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
