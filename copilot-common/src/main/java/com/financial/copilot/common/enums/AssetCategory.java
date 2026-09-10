package com.financial.copilot.common.enums;

import lombok.Getter;

/**
 * 金融资产大类枚举
 * 统一投研底座核心抽象
 */
@Getter
public enum AssetCategory {

    FUND("公募基金", "证券投资基金产品及基金经理"),
    STOCK("股票", "A股/港美股上市公司标的 (规划中)"),
    FUTURES("期货", "大宗商品与金融衍生品期货标的 (规划中)"),
    WEALTH_MANAGEMENT("银行理财", "银行理财子公司与信托资产 (规划中)");

    private final String displayName;
    private final String description;

    AssetCategory(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
}
