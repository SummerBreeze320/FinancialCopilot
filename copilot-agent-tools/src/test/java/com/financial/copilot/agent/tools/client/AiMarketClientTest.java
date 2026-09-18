package com.financial.copilot.agent.tools.client;

import com.financial.copilot.agent.tools.registry.ToolProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Wind AIMarket 工具市场客户端测试")
class AiMarketClientTest {

    private AiMarketClient aiMarketClient;
    private ToolProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ToolProperties();
        properties.getAimarket().setServiceUrl("https://127.0.0.1:65534/aimarket/mcp");
        properties.getAimarket().setTimeoutSeconds(1);
        aiMarketClient = new AiMarketClient(properties, WebClient.builder());
    }

    @Test
    @DisplayName("测试当服务地址未配置时安全返回空结果")
    void testCallSafeFallbackWhenNotConfigured() {
        properties.getAimarket().setServiceUrl("");
        Map<String, Object> result = aiMarketClient.call("session-001", "fin_doc_searchV3", Map.of("query", "半导体龙头研报"));

        assertNotNull(result);
        assertEquals("", result.get("tool_text"));
        assertNull(result.get("tool_payload"));
    }

    @Test
    @DisplayName("测试网络无法连通时具备安全降级机制不抛出致命异常")
    void testCallSafeFallbackWhenOffline() {
        Map<String, Object> result = aiMarketClient.call("session-001", "fin_doc_searchV3", Map.of("query", "新能源基金调仓"));

        assertNotNull(result);
        assertNotNull(result.get("toolRequest"));
        assertEquals("", result.get("tool_text"));
        assertNotNull(result.get("error"));
    }

    @Test
    @DisplayName("测试请求入参字典与会话标准化")
    void testNormalizeToolRequest() {
        Map<String, Object> result = aiMarketClient.call("", "aggregate_search", null);
        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> req = (Map<String, Object>) result.get("toolRequest");
        assertEquals("aggregate_search", req.get("name"));
        assertNotNull(req.get("arguments"));
    }
}
