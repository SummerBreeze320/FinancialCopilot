package com.financial.copilot.domain.wealth.strategy;

import com.financial.copilot.common.enums.AssetCategory;
import com.financial.copilot.common.model.AssetProfile;
import com.financial.copilot.domain.core.strategy.AssetDomainStrategy;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * <h1>银行理财资产大类领域策略实现 (Wealth Domain Strategy)</h1>
 * <p>
 * 职责：向投研总线注册银行理财（WEALTH_MANAGEMENT）领域的接入策略。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class WealthDomainStrategy implements AssetDomainStrategy {

    @Override
    public AssetCategory getSupportedCategory() {
        return AssetCategory.WEALTH_MANAGEMENT;
    }

    @Override
    public Optional<AssetProfile> getAssetProfile(String assetCode) {
        return Optional.of(AssetProfile.builder()
                .assetCode(assetCode)
                .assetName("银行理财产品(" + assetCode + ")")
                .category(AssetCategory.WEALTH_MANAGEMENT)
                .issuerOrExchange("银行理财子公司")
                .benchmarkOrDescription("净值型理财产品")
                .build());
    }

    @Override
    public List<AssetProfile> searchAssets(String keyword) {
        return Collections.emptyList();
    }
}
