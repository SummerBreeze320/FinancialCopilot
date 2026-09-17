package com.financial.copilot.agent.core.dag.planner.tool;

import com.financial.copilot.common.enums.AssetCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <h1>金融能力探测注册工具单元测试 (lite-mysql 版)</h1>
 *
 * @author FinancialCopilot
 */
class CapabilityRegistryToolTest {

    @Test
    @DisplayName("测试探测系统默认挂载的 HTTP 配置化工具能力")
    void testListActiveCapabilities() {
        CapabilityRegistryTool tool = new CapabilityRegistryTool();
        List<CapabilityRegistryTool.CapabilityDescriptor> list = tool.listActiveCapabilities();

        assertThat(list).hasSize(1);
        assertThat(list).allMatch(CapabilityRegistryTool.CapabilityDescriptor::isAvailable);
        assertTrue(tool.isAssetCategorySupported(AssetCategory.FUND));
        assertFalse(tool.isAssetCategorySupported(AssetCategory.STOCK));
        assertFalse(tool.isAssetCategorySupported(AssetCategory.FUTURES));

        CapabilityRegistryTool.CapabilityDescriptor fundDesc = list.getFirst();
        assertThat(fundDesc.capabilityName()).isEqualTo("FundHttpToolSet");
        assertThat(fundDesc.supportedOperations()).contains("ANALYSIS", "COMPARISON", "WORKSPACE_CARD");
    }
}

