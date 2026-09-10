package com.financial.copilot.domain.core.registry;

import com.financial.copilot.common.enums.AssetCategory;
import com.financial.copilot.domain.core.strategy.AssetDomainStrategy;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 资产领域策略注册中心
 * 负责在运行时根据任务意图动态路由到对应的资产领域实现 (Fund, Stock, Futures, Wealth)
 */
@Component
public class AssetDomainRegistry {

    private final Map<AssetCategory, AssetDomainStrategy> strategies = new EnumMap<>(AssetCategory.class);

    public AssetDomainRegistry(List<AssetDomainStrategy> strategyList) {
        if (strategyList != null) {
            for (AssetDomainStrategy strategy : strategyList) {
                strategies.put(strategy.getSupportedCategory(), strategy);
            }
        }
    }

    /**
     * 获取指定资产大类的处理策略
     */
    public Optional<AssetDomainStrategy> getStrategy(AssetCategory category) {
        return Optional.ofNullable(strategies.get(category));
    }

    /**
     * 获取必选策略，未注册时抛出友好异常
     */
    public AssetDomainStrategy getRequiredStrategy(AssetCategory category) {
        return getStrategy(category)
                .orElseThrow(() -> new UnsupportedOperationException("金融投研平台尚未支持该资产大类: " + category.getDisplayName()));
    }
}
