package com.financial.copilot.common.enums;

import lombok.Getter;

/**
 * 金融资产大类枚举
 * 统一投研底座核心抽象
 */
@Getter
public enum AssetCategory {

    FUND("公募基金", "证券投资基金产品及基金经理");

    private final String displayName;
    private final String description;

    AssetCategory(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
}
