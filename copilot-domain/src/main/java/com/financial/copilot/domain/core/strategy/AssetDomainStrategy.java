package com.financial.copilot.domain.core.strategy;

import com.financial.copilot.common.enums.AssetCategory;
import com.financial.copilot.common.model.AssetProfile;

import java.util.List;
import java.util.Optional;

/**
 * 资产领域策略 SPI 契约
 * 任何金融资产类别 (Fund, Stock, Futures, Wealth) 均通过实现此策略挂载到投研平台
 */
public interface AssetDomainStrategy {

    /**
     * 该策略支持的资产大类
     */
    AssetCategory getSupportedCategory();

    /**
     * 根据资产代码查询资产简档
     */
    Optional<AssetProfile> getAssetProfile(String assetCode);

    /**
     * 关键字模糊搜索标的
     */
    List<AssetProfile> searchAssets(String keyword);
}
