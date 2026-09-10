package com.financial.copilot.domain.stock.strategy;

import com.financial.copilot.common.enums.AssetCategory;
import com.financial.copilot.common.model.AssetProfile;
import com.financial.copilot.domain.core.strategy.AssetDomainStrategy;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * <h1>股票资产大类领域策略实现 (规划中/扩展插槽)</h1>
 * <p>
 * 为投研平台挂载股票业务线（STOCK）。当前返回标准扩展桩，未来无缝对接行情库即可完整激活。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class StockDomainStrategy implements AssetDomainStrategy {

    @Override
    public AssetCategory getSupportedCategory() {
        return AssetCategory.STOCK;
    }

    @Override
    public Optional<AssetProfile> getAssetProfile(String assetCode) {
        // 股票标的简档查询扩展点
        return Optional.of(AssetProfile.builder()
                .assetCode(assetCode)
                .assetName("股票标的(" + assetCode + ")")
                .category(AssetCategory.STOCK)
                .issuerOrExchange("A股交易所")
                .benchmarkOrDescription("沪深300指数成份股")
                .build());
    }

    @Override
    public List<AssetProfile> searchAssets(String keyword) {
        return Collections.emptyList();
    }
}
