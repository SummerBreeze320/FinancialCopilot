package com.financial.copilot.agent.core.dag.planner.tool;

import com.financial.copilot.common.enums.AssetCategory;
import com.financial.copilot.domain.fund.port.FundDataPort;
import com.financial.copilot.domain.graph.port.FinancialGraphPort;
import com.financial.copilot.domain.stock.port.StockDataPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>金融能力探测注册工具单元测试</h1>
 *
 * @author FinancialCopilot
 */
class CapabilityRegistryToolTest {

    @Test
    @DisplayName("测试无端口注入时降级容错并标记端口不可用")
    void testListActiveCapabilitiesWithNoPorts() {
        CapabilityRegistryTool tool = new CapabilityRegistryTool();
        List<CapabilityRegistryTool.CapabilityDescriptor> list = tool.listActiveCapabilities();

        assertThat(list).hasSize(3);
        assertThat(list).allMatch(c -> !c.isAvailable());
        assertFalse(tool.isAssetCategorySupported(AssetCategory.FUND));
        assertFalse(tool.isAssetCategorySupported(AssetCategory.STOCK));
        assertFalse(tool.isAssetCategorySupported(AssetCategory.FUTURES));
    }

    @Test
    @DisplayName("测试注入活跃端口时正确识别就绪状态与支持操作")
    void testListActiveCapabilitiesWithMockedPorts() {
        FundDataPort fundPort = Mockito.mock(FundDataPort.class);
        FinancialGraphPort graphPort = Mockito.mock(FinancialGraphPort.class);
        StockDataPort stockPort = Mockito.mock(StockDataPort.class);

        CapabilityRegistryTool tool = new CapabilityRegistryTool(fundPort, graphPort, stockPort);
        List<CapabilityRegistryTool.CapabilityDescriptor> list = tool.listActiveCapabilities();

        assertThat(list).hasSize(3);
        assertThat(list).allMatch(CapabilityRegistryTool.CapabilityDescriptor::isAvailable);
        assertTrue(tool.isAssetCategorySupported(AssetCategory.FUND));
        assertTrue(tool.isAssetCategorySupported(AssetCategory.STOCK));
        assertFalse(tool.isAssetCategorySupported(AssetCategory.FUTURES));

        CapabilityRegistryTool.CapabilityDescriptor fundDesc = list.stream()
                .filter(c -> "FundDataPort".equals(c.capabilityName()))
                .findFirst()
                .orElseThrow();
        assertThat(fundDesc.supportedOperations()).contains("SCREENING", "NAV_HISTORY");
    }
}
