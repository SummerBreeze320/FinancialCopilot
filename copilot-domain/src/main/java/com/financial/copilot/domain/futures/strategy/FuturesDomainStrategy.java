package com.financial.copilot.domain.futures.strategy;

import com.financial.copilot.common.enums.AssetCategory;
import com.financial.copilot.common.model.AssetProfile;
import com.financial.copilot.domain.core.strategy.AssetDomainStrategy;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * <h1>期货资产大类领域策略实现 (Futures Domain Strategy)</h1>
 * <p>
 * 职责：向投研总线注册期货衍生品（FUTURES）领域的接入策略。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class FuturesDomainStrategy implements AssetDomainStrategy {

    @Override
    public AssetCategory getSupportedCategory() {
        return AssetCategory.FUTURES;
    }

    @Override
    public Optional<AssetProfile> getAssetProfile(String assetCode) {
        return Optional.of(AssetProfile.builder()
                .assetCode(assetCode)
                .assetName("期货合约(" + assetCode + ")")
                .category(AssetCategory.FUTURES)
                .issuerOrExchange("期货交易所")
                .benchmarkOrDescription("商品/金融期货衍生品")
                .build());
    }

    @Override
    public List<AssetProfile> searchAssets(String keyword) {
        return Collections.emptyList();
    }
}
