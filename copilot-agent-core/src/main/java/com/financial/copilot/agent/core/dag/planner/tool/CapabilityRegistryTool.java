package com.financial.copilot.agent.core.dag.planner.tool;

import com.financial.copilot.common.enums.AssetCategory;
import com.financial.copilot.domain.fund.port.FundDataPort;
import com.financial.copilot.domain.graph.port.FinancialGraphPort;
import com.financial.copilot.domain.stock.port.StockDataPort;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <h1>系统底层金融数据能力探测与注册工具 (CapabilityRegistryTool)</h1>
 * <p>
 * 遵循四层架构隔离规范，供 {@code GraphPlanner} 在建图与动态自适应阶段探测当前系统
 * 挂载的底层数据端口（如公募基金端口、知识图谱端口、股票/衍生品端口）及算力支持，
 * 避免规划器生成底层无法执行的空中楼阁节点。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class CapabilityRegistryTool {

    /**
     * 能力描述符传输模型
     *
     * @param assetCategory        关联资产大类
     * @param capabilityName       能力名称
     * @param isAvailable          当前环境是否已挂载就绪
     * @param supportedOperations  支持的原子操作集（如 SCREENING, QUANT, HOLDINGS 等）
     * @param description          能力简介
     */
    @Builder
    public record CapabilityDescriptor(
            AssetCategory assetCategory,
            String capabilityName,
            boolean isAvailable,
            List<String> supportedOperations,
            String description
    ) {}

    private final FundDataPort fundDataPort;
    private final FinancialGraphPort financialGraphPort;
    private final StockDataPort stockDataPort;

    public CapabilityRegistryTool() {
        this(null, null, null);
    }

    @Autowired
    public CapabilityRegistryTool(
            @Autowired(required = false) FundDataPort fundDataPort,
            @Autowired(required = false) FinancialGraphPort financialGraphPort,
            @Autowired(required = false) StockDataPort stockDataPort
    ) {
        this.fundDataPort = fundDataPort;
        this.financialGraphPort = financialGraphPort;
        this.stockDataPort = stockDataPort;
    }

    /**
     * 查询系统当前已激活就绪的全部底层金融服务端口能力
     *
     * @return 活跃能力清单
     */
    public List<CapabilityDescriptor> listActiveCapabilities() {
        List<CapabilityDescriptor> capabilities = new ArrayList<>();

        // 1. 公募基金领域底座
        capabilities.add(CapabilityDescriptor.builder()
                .assetCategory(AssetCategory.FUND)
                .capabilityName("FundDataPort")
                .isAvailable(fundDataPort != null)
                .supportedOperations(List.of("SCREENING", "NAV_HISTORY", "PORTFOLIO_HOLDINGS", "MANAGER_RATINGS"))
                .description("提供公募基金多维选基、净值序列、定期报告与基金经理画像底层能力")
                .build());

        // 2. 金融知识图谱底座
        capabilities.add(CapabilityDescriptor.builder()
                .assetCategory(AssetCategory.FUND)
                .capabilityName("FinancialGraphPort")
                .isAvailable(financialGraphPort != null)
                .supportedOperations(List.of("SHARED_HOLDINGS", "MANAGER_NETWORK", "INDUSTRY_EXPOSURE"))
                .description("提供基金重仓股票穿透、关联基金图谱与行业暴露深度穿透")
                .build());

        // 3. 股票与多资产扩展底座
        capabilities.add(CapabilityDescriptor.builder()
                .assetCategory(AssetCategory.STOCK)
                .capabilityName("StockDataPort")
                .isAvailable(stockDataPort != null)
                .supportedOperations(List.of("STOCK_SCREENING", "FINANCIAL_METRICS", "VALUATION"))
                .description("提供A股/港股标的指标与基本面财务数据能力")
                .build());

        return Collections.unmodifiableList(capabilities);
    }

    /**
     * 判定目标资产大类是否已具备完整数据驱动条件
     *
     * @param category 资产大类
     * @return true 若具备可用端口
     */
    public boolean isAssetCategorySupported(AssetCategory category) {
        if (category == null) return false;
        return switch (category) {
            case FUND -> fundDataPort != null;
            case STOCK -> stockDataPort != null;
            case FUTURES, WEALTH_MANAGEMENT -> false;
        };
    }
}
