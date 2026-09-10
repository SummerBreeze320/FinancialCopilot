package com.financial.copilot.domain.fund.strategy;

import com.financial.copilot.common.enums.AssetCategory;
import com.financial.copilot.common.model.AssetProfile;
import com.financial.copilot.domain.core.strategy.AssetDomainStrategy;
import com.financial.copilot.domain.entity.FundInfo;
import com.financial.copilot.domain.port.FundDataPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 公募基金领域策略实现
 * 首期深度实施资产大类：FUND
 */
@Component
public class FundDomainStrategy implements AssetDomainStrategy {

    private final FundDataPort fundDataPort;

    public FundDomainStrategy(FundDataPort fundDataPort) {
        this.fundDataPort = fundDataPort;
    }

    @Override
    public AssetCategory getSupportedCategory() {
        return AssetCategory.FUND;
    }

    @Override
    public Optional<AssetProfile> getAssetProfile(String assetCode) {
        return fundDataPort.getFundByCode(assetCode)
                .map(this::toAssetProfile);
    }

    @Override
    public List<AssetProfile> searchAssets(String keyword) {
        // 模糊搜索基金
        return fundDataPort.getFundByCode(keyword)
                .map(f -> List.of(toAssetProfile(f)))
                .orElseGet(List::of);
    }

    private AssetProfile toAssetProfile(FundInfo fund) {
        return AssetProfile.builder()
                .assetCode(fund.getFundCode())
                .assetName(fund.getFundName())
                .category(AssetCategory.FUND)
                .issuerOrExchange(fund.getManagementCompanyId())
                .latestPriceOrNav(null) // 可通过净值时序动态扩充
                .benchmarkOrDescription(fund.getTrackingBenchmark())
                .build();
    }
}
