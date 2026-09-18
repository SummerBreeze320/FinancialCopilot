package com.financial.copilot.agent.tools.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.tools.registry.ToolProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Wind MCP JSON-RPC 2.0 客户端测试")
class WindMcpClientTest {

    private WindMcpClient mcpClient;
    private ToolProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ToolProperties();
        properties.getMcp().setBaseUrl("https://114.80.154.45/Wind.MCP.Server/vserver");
        mcpClient = new WindMcpClient(properties, WebClient.builder());
    }

    @Test
    @DisplayName("测试当服务地址未显式连通时，自动执行安全降级而不抛出异常")
    void testCallSafeFallbackWhenOffline() {
        // 使用一个未配置的或者本地无法连通的 server
        Map<String, Object> result = mcpClient.call(
                "test-session-123",
                "wind-fund-analysis",
                "fund_get_similar",
                Map.of("windCodes", List.of("005827.OF"))
        );

        assertNotNull(result);
        assertEquals("wind-fund-analysis", result.get("server"));
        assertNotNull(result.get("toolRequest"));
        assertNotNull(result.get("content"));
        assertNotNull(result.get("summary"));
    }

    @Test
    @DisplayName("测试 MCP Server 动态路由解析")
    void testServerRouting() {
        Map<String, String> servers = properties.getMcp().getServers();
        assertTrue(servers.containsKey("wind-fund-analysis"));
        assertTrue(servers.containsKey("wind-fund-holdings"));
        assertTrue(servers.containsKey("wind-fund-data"));
        assertTrue(servers.get("wind-fund-analysis").contains("vserver_fund_analysistest"));
    }

    @Test
    @DisplayName("测试 MCP 工具列表查询安全回退")
    void testListToolsSafeFallback() {
        List<Map<String, Object>> tools = mcpClient.listTools("test-session", "wind-fund-analysis");
        assertNotNull(tools);
        // 脱机环境下应安全返回空列表，不中断业务
        assertTrue(tools.isEmpty());
    }
}
