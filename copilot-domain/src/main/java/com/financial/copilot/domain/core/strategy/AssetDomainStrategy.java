package com.financial.copilot.domain.core.strategy;

import com.financial.copilot.common.enums.AssetCategory;
import com.financial.copilot.common.model.AssetProfile;

import java.util.List;
import java.util.Optional;

/**
 * <h1>金融大类领域策略 SPI 契约 (Asset Domain Strategy SPI)</h1>
 * <p>
 * 职责：作为金融投研底座的核心抽象 SPI。无论是公募基金 (FUND)、股票 (STOCK)、期货 (FUTURES)
 * 还是银行理财 (WEALTH_MANAGEMENT)，均通过实现此策略契约接入到多智能体投研协同总线中。
 * </p>
 *
 * @author FinancialCopilot
 */
public interface AssetDomainStrategy {

    /**
     * 获取该策略所支持的金融资产大类
     *
     * @return 资产大类枚举 {@link AssetCategory}
     */
    AssetCategory getSupportedCategory();

    /**
     * 根据资产代码查询资产基本概况简档
     *
     * @param assetCode 标的代码（如 "005827" 或 "600519"）
     * @return 资产简档包装对象 {@link AssetProfile}
     */
    Optional<AssetProfile> getAssetProfile(String assetCode);

    /**
     * 关键字模糊搜索该资产大类下的候选标的
     *
     * @param keyword 搜索关键字（如 "医药"、"半导体"、"招商"）
     * @return 命中资产的简档列表
     */
    List<AssetProfile> searchAssets(String keyword);
}
