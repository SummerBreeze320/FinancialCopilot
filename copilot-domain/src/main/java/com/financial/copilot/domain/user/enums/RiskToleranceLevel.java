package com.financial.copilot.domain.user.enums;

import lombok.Getter;

/**
 * <h1>投资者风险承受能力与偏好评估等级 (Risk Tolerance Level)</h1>
 * <p>
 * 严格遵照中国证券基金业协会及金融适当性管理规范划分：
 * <ul>
 *   <li>C1: 保守型 - 本金安全第一，极低波动容忍，偏好货币基金与国债</li>
 *   <li>C2: 相对保守型 - 追求稳健收益，低回撤容忍，偏好固收+与纯债基金</li>
 *   <li>C3: 平衡型 - 追求资产保值增值，中度波动容忍，偏好股债平衡混合型基金</li>
 *   <li>C4: 相对积极型 - 追求超额收益，较高波动容忍，偏好偏股混合型与行业主题基金</li>
 *   <li>C5: 进取型 - 追求高杠杆或最大资本增值，高风险高波动容忍，偏好高弹性权益资产与衍生品</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Getter
public enum RiskToleranceLevel {

    C1("C1", "保守型", "本金安全优先，偏好低波动与货币纯债资产"),
    C2("C2", "相对保守型", "稳健防守优先，可承受极小净值回撤"),
    C3("C3", "平衡型", "兼顾收益与回撤，偏好股债均衡配置"),
    C4("C4", "相对积极型", "偏好优质成长与行业主题，可承受阶段性大幅回撤"),
    C5("C5", "进取型", "追求最大收益弹性，具备极强风险承受能力");

    private final String code;
    private final String displayName;
    private final String description;

    RiskToleranceLevel(String code, String displayName, String description) {
        this.code = code;
        this.displayName = displayName;
        this.description = description;
    }

    public static RiskToleranceLevel fromCode(String code) {
        if (code == null) {
            return C3;
        }
        for (RiskToleranceLevel level : values()) {
            if (level.getCode().equalsIgnoreCase(code) || level.name().equalsIgnoreCase(code)) {
                return level;
            }
        }
        return C3;
    }
}
