package com.financial.copilot.agent.core.dag.planner.tool;

import com.financial.copilot.common.enums.AssetCategory;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <h1>系统底层金融数据能力探测与注册工具 (CapabilityRegistryTool)</h1>
 * <p>
 * 供 {@code GraphPlanner} 在建图阶段探测系统挂载的配置化工具及业务算力。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class CapabilityRegistryTool {

    @Builder
    public record CapabilityDescriptor(
            AssetCategory assetCategory,
            String capabilityName,
            boolean isAvailable,
            List<String> supportedOperations,
            String description
    ) {}

    public CapabilityRegistryTool() {
    }

    /**
     * 查询系统当前已激活就绪的全部底层金融服务端口能力
     *
     * @return 活跃能力清单
     */
    public List<CapabilityDescriptor> listActiveCapabilities() {
        List<CapabilityDescriptor> capabilities = new ArrayList<>();

        // 1. 公募基金配置化 HTTP 工具集
        capabilities.add(CapabilityDescriptor.builder()
                .assetCategory(AssetCategory.FUND)
                .capabilityName("FundHttpToolSet")
                .isAvailable(true)
                .supportedOperations(List.of("ANALYSIS", "COMPARISON", "WORKSPACE_CARD"))
                .description("提供基于 HTTP / MCP 的公募基金配置化全景投研与工作台分析组件")
                .build());

        return Collections.unmodifiableList(capabilities);
    }

    /**
     * 判定目标资产大类是否具备执行条件
     *
     * @param category 资产大类
     * @return true 若具备可用能力
     */
    public boolean isAssetCategorySupported(AssetCategory category) {
        return category == AssetCategory.FUND;
    }
}
