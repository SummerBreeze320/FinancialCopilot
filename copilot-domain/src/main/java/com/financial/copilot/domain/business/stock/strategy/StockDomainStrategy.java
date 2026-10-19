package com.financial.copilot.domain.business.stock.strategy;

import com.financial.copilot.common.enums.AssetCategory;
import com.financial.copilot.common.model.AssetProfile;
import com.financial.copilot.domain.business.stock.entity.StockInfo;
import com.financial.copilot.domain.business.stock.port.StockDataPort;
import com.financial.copilot.domain.shared.core.strategy.AssetDomainStrategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * <h1>股票资产大类领域策略实现</h1>
 * <p>
 * 为投研平台挂载股票业务线（STOCK），通过 {@link StockDataPort} 接入真实行情数据，
 * 提供个股档案查询与关键字搜索能力。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class StockDomainStrategy implements AssetDomainStrategy {

    private final StockDataPort stockDataPort;

    public StockDomainStrategy(StockDataPort stockDataPort) {
        this.stockDataPort = stockDataPort;
    }

    @Override
    public AssetCategory getSupportedCategory() {
        return AssetCategory.STOCK;
    }

    @Override
    public Optional<AssetProfile> getAssetProfile(String assetCode) {
        return stockDataPort.getStockByCode(assetCode)
                .map(this::toAssetProfile);
    }

    @Override
    public List<AssetProfile> searchAssets(String keyword) {
        return stockDataPort.screenStocks(
                new com.financial.copilot.common.stock.dto.StockScreeningCriteria(
                        null, keyword, null, null, null, null, null, null, null, 20
                )
        ).stream().map(this::toAssetProfile).toList();
    }

    private AssetProfile toAssetProfile(StockInfo info) {
        return AssetProfile.builder()
                .assetCode(info.getStockCode())
                .assetName(info.getStockName())
                .category(AssetCategory.STOCK)
                .issuerOrExchange(info.getExchange())
                .benchmarkOrDescription(info.getPrimarySector())
                .build();
    }
}
