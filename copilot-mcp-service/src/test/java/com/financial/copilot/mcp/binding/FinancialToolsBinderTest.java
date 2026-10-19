package com.financial.copilot.mcp.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundScreeningTool;
import com.financial.copilot.agent.tools.stock.StockQuantAnalysisTool;
import com.financial.copilot.agent.tools.stock.StockTechnicalAnalysisTool;
import com.financial.copilot.common.fund.dto.FundScreeningCriteria;
import com.financial.copilot.mcp.config.McpServerProperties;
import com.financial.copilot.mcp.protocol.McpToolDefinition;
import com.financial.copilot.mcp.registry.DynamicToolConfig;
import com.financial.copilot.mcp.registry.McpToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <h1>内置金融工具自动绑定与 Schema 校验测试</h1>
 */
class FinancialToolsBinderTest {

    @Test
    @DisplayName("测试金融工具与动态工具全量绑定、Schema 格式及执行调用")
    void shouldBindFinancialToolsAndExecute() {
        McpToolRegistry registry = new McpToolRegistry();
        McpServerProperties properties = new McpServerProperties();

        // 配置 1 个动态工具
        properties.setDynamicTools(List.of(
                DynamicToolConfig.builder()
                        .name("custom_market_sentiment")
                        .description("情绪分析动态工具")
                        .endpoint("http://mock-api.com/sentiment")
                        .parameters(List.of(
                                new DynamicToolConfig.ParameterConfig("sector", "string", "板块", true)
                        ))
                        .build()
        ));

        FinancialToolsBinder binder = new FinancialToolsBinder(
                registry, properties, WebClient.builder(), new ObjectMapper()
        );

        // Mock 内置工具
        FundQuantAnalysisTool mockFundQuant = mock(FundQuantAnalysisTool.class);
        when(mockFundQuant.getFundMetrics(eq("005827"), any(), any()))
                .thenReturn("{\"fundCode\":\"005827\",\"annualizedReturn\":22.5}");

        FundScreeningTool mockScreening = mock(FundScreeningTool.class);
        when(mockScreening.screenFunds(any(FundScreeningCriteria.class)))
                .thenReturn("[{\"fundCode\":\"005827\"}]");

        FundHoldingsQueryTool mockHoldings = mock(FundHoldingsQueryTool.class);
        when(mockHoldings.getTopHoldings(eq("005827"), any()))
                .thenReturn("{\"holdings\":[{\"stockCode\":\"600519\",\"ratio\":9.5}]}");

        StockQuantAnalysisTool mockStockQuant = mock(StockQuantAnalysisTool.class);
        when(mockStockQuant.getStockMetrics(eq("600519")))
                .thenReturn("{\"stockCode\":\"600519\",\"pe\":28.4}");

        StockTechnicalAnalysisTool mockTechnical = mock(StockTechnicalAnalysisTool.class);
        when(mockTechnical.getTechnicalIndicators(eq("600519")))
                .thenReturn("{\"macd\":\"GOLDEN_CROSS\"}");

        binder.setFundQuant(mockFundQuant);
        binder.setFundScreening(mockScreening);
        binder.setFundHoldings(mockHoldings);
        binder.setStockQuant(mockStockQuant);
        binder.setStockTechnical(mockTechnical);

        // 执行绑定
        binder.bindAll();

        // 验证已注册工具总数 (5 个内置 + 1 个动态 = 6)
        assertEquals(6, registry.count());
        assertTrue(registry.containsTool("fund_metrics"));
        assertTrue(registry.containsTool("screen_funds"));
        assertTrue(registry.containsTool("fund_holdings"));
        assertTrue(registry.containsTool("stock_metrics"));
        assertTrue(registry.containsTool("stock_technical"));
        assertTrue(registry.containsTool("custom_market_sentiment"));

        // 验证 fund_metrics 的 Schema
        List<McpToolDefinition> tools = registry.listTools();
        McpToolDefinition fundDef = tools.stream()
                .filter(t -> "fund_metrics".equals(t.getName()))
                .findFirst()
                .orElseThrow();

        assertNotNull(fundDef.getInputSchema());
        assertEquals("object", fundDef.getInputSchema().get("type"));
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) fundDef.getInputSchema().get("required");
        assertTrue(required.contains("fundCode"));

        // 验证调用 fund_metrics
        StepVerifier.create(registry.execute("fund_metrics", Map.of("fundCode", "005827")))
                .assertNext(res -> {
                    assertFalse(res.getIsError());
                    assertTrue(res.getContent().get(0).getText().contains("22.5"));
                })
                .verifyComplete();

        // 验证调用 stock_metrics
        StepVerifier.create(registry.execute("stock_metrics", Map.of("stockCode", "600519")))
                .assertNext(res -> {
                    assertFalse(res.getIsError());
                    assertTrue(res.getContent().get(0).getText().contains("28.4"));
                })
                .verifyComplete();

        // 验证调用 stock_technical
        StepVerifier.create(registry.execute("stock_technical", Map.of("stockCode", "600519")))
                .assertNext(res -> {
                    assertFalse(res.getIsError());
                    assertTrue(res.getContent().get(0).getText().contains("GOLDEN_CROSS"));
                })
                .verifyComplete();
    }
}
