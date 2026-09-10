package com.financial.copilot.domain.fund.strategy;

import com.financial.copilot.common.enums.AssetCategory;
import com.financial.copilot.common.model.AssetProfile;
import com.financial.copilot.domain.core.strategy.AssetDomainStrategy;
import com.financial.copilot.domain.fund.entity.FundInfo;
import com.financial.copilot.domain.fund.port.FundDataPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * <h1>公募基金领域策略落地实现 (深度实施)</h1>
 * <p>
 * 实现 {@link AssetDomainStrategy} 契约，将公募基金专属的标的查询、净值时序及持仓事实挂载至通用投研注册中心。
 * </p>
 *
 * @author FinancialCopilot
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
                .latestPriceOrNav(null)
                .benchmarkOrDescription(fund.getTrackingBenchmark())
                .build();
    }
}
