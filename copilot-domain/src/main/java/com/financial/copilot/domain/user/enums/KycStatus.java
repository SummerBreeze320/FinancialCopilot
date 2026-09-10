package com.financial.copilot.domain.user.enums;

import lombok.Getter;

/**
 * <h1>金融实名认证 (KYC) 审核生命周期状态枚举</h1>
 * <p>
 * 遵循金融合规监管要求，支持未认证、审核中、已认证与驳回状态转移。
 * </p>
 *
 * @author FinancialCopilot
 */
@Getter
public enum KycStatus {

    UNVERIFIED("未实名认证"),
    PENDING("实名审核中"),
    VERIFIED("实名认证已通过"),
    REJECTED("实名认证已驳回");

    private final String description;

    KycStatus(String description) {
        this.description = description;
    }
}
