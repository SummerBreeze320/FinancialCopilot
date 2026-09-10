package com.financial.copilot.domain.core.registry;

import com.financial.copilot.common.enums.AssetCategory;
import com.financial.copilot.domain.core.strategy.AssetDomainStrategy;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <h1>金融资产领域策略统一注册中心 (Asset Domain Registry)</h1>
 * <p>
 * 职责：负责在 Spring 容器启动时自动收集所有已挂载的资产大类策略实现类（公募基金、股票、期货、理财等），
 * 在投研执行期根据任务意图与资产类型动态路由到对应的资产领域实现。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class AssetDomainRegistry {

    /**
     * 资产大类策略字典表，基于高性能 EnumMap
     */
    private final Map<AssetCategory, AssetDomainStrategy> strategies = new EnumMap<>(AssetCategory.class);

    /**
     * 构造函数，自动注入 Spring 容器中所有实现了 {@link AssetDomainStrategy} 的 Bean
     *
     * @param strategyList 策略实现类列表
     */
    public AssetDomainRegistry(List<AssetDomainStrategy> strategyList) {
        if (strategyList != null) {
            for (AssetDomainStrategy strategy : strategyList) {
                strategies.put(strategy.getSupportedCategory(), strategy);
            }
        }
    }

    /**
     * 获取指定资产大类的处理策略
     *
     * @param category 资产大类枚举
     * @return 策略实例的可空包装
     */
    public Optional<AssetDomainStrategy> getStrategy(AssetCategory category) {
        return Optional.ofNullable(strategies.get(category));
    }

    /**
     * 获取必选策略，未注册时抛出友好异常
     *
     * @param category 资产大类枚举
     * @return 资产策略实例
     * @throws UnsupportedOperationException 若对应资产大类未注册则抛出异常
     */
    public AssetDomainStrategy getRequiredStrategy(AssetCategory category) {
        return getStrategy(category)
                .orElseThrow(() -> new UnsupportedOperationException("金融投研平台尚未支持该资产大类: " + category.getDisplayName()));
    }
}
