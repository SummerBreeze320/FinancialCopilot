package com.financial.copilot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.tools.distiller.ComponentDataDistiller;
import com.financial.copilot.agent.tools.executor.AiMarketToolExecutor;
import com.financial.copilot.agent.tools.executor.HttpToolExecutor;
import com.financial.copilot.agent.tools.executor.LocalToolExecutor;
import com.financial.copilot.agent.tools.executor.McpToolExecutor;
import com.financial.copilot.agent.tools.model.ToolExecuteRequest;
import com.financial.copilot.agent.tools.model.ToolExecuteResult;
import com.financial.copilot.agent.tools.model.ToolSourceMode;
import com.financial.copilot.agent.tools.registry.ToolDefinitionLoader;
import com.financial.copilot.agent.tools.registry.ToolProperties;
import com.financial.copilot.agent.tools.registry.ToolRegistry;
import com.financial.copilot.agent.tools.router.ToolExecutorRouter;
import com.financial.copilot.agent.tools.workspace.ComponentInstanceIdFactory;
import com.financial.copilot.agent.tools.workspace.ConfiguredToolWorkspaceBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("两级分类 Tool 执行路由与双轨交付集成测试")
class ToolExecutorRouterTest {

    private ToolExecutorRouter router;

    @BeforeEach
    void setUp() {
        ToolProperties properties = new ToolProperties();
        properties.setConfigPattern("classpath*:config/tools/**/*.json");

        ToolDefinitionLoader loader = new ToolDefinitionLoader(
                new DefaultResourceLoader(),
                new ObjectMapper(),
                properties
        );

        ToolRegistry registry = new ToolRegistry(loader);
        registry.init();

        ComponentDataDistiller distiller = new ComponentDataDistiller();
        ComponentInstanceIdFactory idFactory = new ComponentInstanceIdFactory();
        ConfiguredToolWorkspaceBuilder workspaceBuilder = new ConfiguredToolWorkspaceBuilder(idFactory);

        LocalToolExecutor localExecutor = new LocalToolExecutor(distiller, workspaceBuilder);
        HttpToolExecutor httpExecutor = new HttpToolExecutor(distiller, workspaceBuilder);
        McpToolExecutor mcpExecutor = new McpToolExecutor(distiller, workspaceBuilder);
        AiMarketToolExecutor aiMarketExecutor = new AiMarketToolExecutor(distiller, workspaceBuilder);

        router = new ToolExecutorRouter(registry, List.of(localExecutor, httpExecutor, mcpExecutor, aiMarketExecutor));
    }

    @Test
    @DisplayName("验证多基金 MCP 工具 (compare_brinson) 双轨执行并产出表格+柱状图组件")
    void testRouteMcpCompareBrinson() {
        ToolExecuteRequest request = ToolExecuteRequest.builder()
                .toolId("compare_brinson")
                .arguments(Map.of("windCodes", List.of("005827.OF", "163402.OF"), "reportDate", "20240630"))
                .sourceMode(ToolSourceMode.EXTERNAL_CONFIGURED)
                .build();

        ToolExecuteResult result = router.routeAndExecute(request);

        assertTrue(result.isSuccess(), "Execution should succeed");
        assertNotNull(result.getTextForLlm(), "LLM text should not be null");
        assertTrue(result.getTextForLlm().contains("Brinson"), "Should contain Brinson conclusions");

        // 验证产出的一组组件 (Components)
        assertNotNull(result.getVisualComponents(), "Visual components list should not be null");
        assertEquals(2, result.getVisualComponents().size(), "compare_brinson must produce both table and chart components");
        assertEquals("brinson", result.getVisualComponents().get(0).getId());
        assertEquals("brinsonChart", result.getVisualComponents().get(1).getId());

        // 验证工作台 Payload 与 References
        assertNotNull(result.getWorkspacePayload(), "Workspace payload should not be null");
        assertEquals("FUND_COMPARISON", result.getWorkspacePayload().type());
        assertEquals(2, result.getWorkspacePayload().components().size());
        assertNotNull(result.getReferences(), "References should not be null");
        assertFalse(result.getReferences().isEmpty());
    }

    @Test
    @DisplayName("验证多基金对比工具 (compare_asset_allocation) 双轨执行并产出7个关联配置组件")
    void testRouteCompareAssetAllocation() {
        ToolExecuteRequest request = ToolExecuteRequest.builder()
                .toolId("compare_asset_allocation")
                .arguments(Map.of("windCodes", List.of("005827.OF", "163402.OF")))
                .sourceMode(ToolSourceMode.EXTERNAL_CONFIGURED)
                .build();

        ToolExecuteResult result = router.routeAndExecute(request);

        assertTrue(result.isSuccess());
        assertNotNull(result.getTextForLlm());
        assertEquals(7, result.getVisualComponents().size(), "compare_asset_allocation must produce all 7 asset allocation components");
    }

    @Test
    @DisplayName("验证基金深度分析工具 (fund_analysis_profile) 双轨执行并产出3个基础信息卡片")
    void testRouteSingleFundProfile() {
        ToolExecuteRequest request = ToolExecuteRequest.builder()
                .toolId("fund_analysis_profile")
                .arguments(Map.of("windCodes", List.of("005827.OF")))
                .sourceMode(ToolSourceMode.EXTERNAL_CONFIGURED)
                .build();

        ToolExecuteResult result = router.routeAndExecute(request);

        assertTrue(result.isSuccess());
        assertNotNull(result.getTextForLlm());
        assertEquals(3, result.getVisualComponents().size(), "fund_analysis_profile must produce 3 profile components");
    }

    @Test
    @DisplayName("验证 LOCAL_PROJECT 模式路由至 LocalToolExecutor")
    void testRouteLocalMode() {
        ToolExecuteRequest request = ToolExecuteRequest.builder()
                .toolId("compare_similar")
                .arguments(Map.of("windCodes", List.of("005827.OF")))
                .sourceMode(ToolSourceMode.LOCAL_PROJECT)
                .build();

        ToolExecuteResult result = router.routeAndExecute(request);

        assertTrue(result.isSuccess());
        assertTrue(result.getTextForLlm().contains("本地引擎执行成功"));
    }

    @Test
    @DisplayName("验证 AIMarket 工具 (fund_doc_search) 路由至 AiMarketToolExecutor 并产出研报检索结果")
    void testRouteAiMarketDocSearch() {
        ToolExecuteRequest request = ToolExecuteRequest.builder()
                .toolId("fund_doc_search")
                .arguments(Map.of("query", "高股息红利资产研报展望"))
                .sourceMode(ToolSourceMode.EXTERNAL_CONFIGURED)
                .build();

        ToolExecuteResult result = router.routeAndExecute(request);

        assertTrue(result.isSuccess());
        assertNotNull(result.getTextForLlm());
        assertTrue(result.getTextForLlm().contains("研报/知识检索结果"));
        assertEquals(1, result.getVisualComponents().size());
        assertEquals("docSearchResult", result.getVisualComponents().get(0).getId());
    }

    @Test
    @DisplayName("验证综合资讯检索 (aggregate_search) 成功路由并构建资讯卡片")
    void testRouteAggregateSearch() {
        ToolExecuteRequest request = ToolExecuteRequest.builder()
                .toolId("aggregate_search")
                .arguments(Map.of("query", "公募基金费率改革最新进展"))
                .sourceMode(ToolSourceMode.EXTERNAL_CONFIGURED)
                .build();

        ToolExecuteResult result = router.routeAndExecute(request);

        assertTrue(result.isSuccess());
        assertNotNull(result.getTextForLlm());
        assertEquals(1, result.getVisualComponents().size());
        assertEquals("aggregateSearchResult", result.getVisualComponents().get(0).getId());
    }

    @Test
    @DisplayName("验证指数编制规则查询 (index_query_description) 成功路由并构建规则卡片")
    void testRouteIndexQueryDescription() {
        ToolExecuteRequest request = ToolExecuteRequest.builder()
                .toolId("index_query_description")
                .arguments(Map.of("indexName", "中证红利"))
                .sourceMode(ToolSourceMode.EXTERNAL_CONFIGURED)
                .build();

        ToolExecuteResult result = router.routeAndExecute(request);

        assertTrue(result.isSuccess());
        assertNotNull(result.getTextForLlm());
        assertEquals(1, result.getVisualComponents().size());
        assertEquals("indexDescriptionResult", result.getVisualComponents().get(0).getId());
    }
}
