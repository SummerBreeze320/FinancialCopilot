package com.financial.copilot.agent.tools.configured;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.tools.configured.model.ToolDefinition;
import com.financial.copilot.agent.tools.configured.model.UITreeComponent;
import com.financial.copilot.agent.tools.configured.registry.ToolDefinitionLoader;
import com.financial.copilot.agent.tools.configured.registry.ToolProperties;
import com.financial.copilot.agent.tools.configured.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("模块化两级分类 Tool 配置加载与注册表单元测试")
class ToolDefinitionLoaderTest {

    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        ToolProperties properties = new ToolProperties();
        properties.setConfigPattern("classpath*:config/tools/**/*.json");

        ToolDefinitionLoader loader = new ToolDefinitionLoader(
                new DefaultResourceLoader(),
                new ObjectMapper(),
                properties
        );

        toolRegistry = new ToolRegistry(loader);
        toolRegistry.init();
    }

    @Test
    @DisplayName("验证两级分类: 13个深度分析工具 + 11个横向对比工具全部成功加载")
    void testLoadModularToolsHierarchy() {
        assertEquals(24, toolRegistry.size(), "Should load exactly 24 modular tool files across two scopes");

        // 验证深度分析体系 (13个工具)
        List<ToolDefinition> analysisTools = toolRegistry.getAnalysisTools();
        assertEquals(13, analysisTools.size(), "Should contain 13 deep analysis tools");

        // 验证横向对标体系 (11个工具)
        List<ToolDefinition> comparisonTools = toolRegistry.getComparisonTools();
        assertEquals(11, comparisonTools.size(), "Should contain 11 comparison tools");
    }

    @Test
    @DisplayName("验证基金深度分析工具与其绑定的组件清单")
    void testSingleFundToolsAndComponents() {
        // 1. 验证基金资料 (fund_analysis_profile): 绑定 3 个组件
        Optional<ToolDefinition> profileOpt = toolRegistry.get("fund_analysis_profile");
        assertTrue(profileOpt.isPresent());
        ToolDefinition profile = profileOpt.get();
        assertEquals("ANALYSIS", profile.getScope());
        assertEquals(3, profile.getComponents().size());
        assertTrue(profile.getComponents().stream().anyMatch(c -> "MoneyBasicInfo".equals(c.getId())));
        assertTrue(profile.getComponents().stream().anyMatch(c -> "MoneyInvestObject".equals(c.getId())));

        // 2. 验证基金历年运作指标变动 (fund_analysis_run_indicators): 绑定 6 个组件
        Optional<ToolDefinition> runOpt = toolRegistry.get("fund_analysis_run_indicators");
        assertTrue(runOpt.isPresent());
        assertEquals(6, runOpt.get().getComponents().size());
        assertTrue(runOpt.get().getComponents().stream().anyMatch(c -> "MoneyHistoryScaleChange".equals(c.getId())));
    }

    @Test
    @DisplayName("验证基金横向对比工具与其绑定的组件清单")
    void testMultiFundToolsAndComponents() {
        // 1. 验证多基金资产与行业配置对比 (compare_asset_allocation): 绑定 7 个组件
        Optional<ToolDefinition> allocOpt = toolRegistry.get("compare_asset_allocation");
        assertTrue(allocOpt.isPresent());
        ToolDefinition alloc = allocOpt.get();
        assertEquals("COMPARISON", alloc.getScope());
        assertEquals(7, alloc.getComponents().size());
        assertTrue(alloc.getComponents().stream().anyMatch(c -> "AssetAllocation".equals(c.getId())));
        assertTrue(alloc.getComponents().stream().anyMatch(c -> "IndustryAllocationForDefault".equals(c.getId())));

        // 2. 验证多基金 Brinson 业绩归因 (compare_brinson): 绑定 2 个组件 (表格 + 柱状图)
        Optional<ToolDefinition> brinsonOpt = toolRegistry.get("compare_brinson");
        assertTrue(brinsonOpt.isPresent());
        ToolDefinition brinson = brinsonOpt.get();
        assertEquals("COMPARISON", brinson.getScope());
        assertEquals(2, brinson.getComponents().size());
        assertTrue(brinson.getComponents().stream().anyMatch(c -> "brinson".equals(c.getId())));
        assertTrue(brinson.getComponents().stream().anyMatch(c -> "brinsonChart".equals(c.getId())));

        // 3. 验证多基金重仓资产对标 (compare_top_holdings): 绑定 4 个组件
        Optional<ToolDefinition> topOpt = toolRegistry.get("compare_top_holdings");
        assertTrue(topOpt.isPresent());
        assertEquals(4, topOpt.get().getComponents().size());

        // 4. 验证跨工具全局查找组件
        Optional<UITreeComponent> topHoldings = toolRegistry.findComponent("TopHoldings");
        assertTrue(topHoldings.isPresent());
        assertEquals("重仓股票", topHoldings.get().getName());
    }
}
