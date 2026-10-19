package com.financial.copilot.mcp.registry;

import com.financial.copilot.mcp.protocol.McpToolCallResult;
import com.financial.copilot.mcp.protocol.McpToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>MCP 工具注册中心与动态配置测试</h1>
 */
class McpToolRegistryTest {

    @Test
    @DisplayName("测试工具手动注册、清单列表与执行调用")
    void shouldRegisterAndExecuteTool() {
        McpToolRegistry registry = new McpToolRegistry();

        McpToolDefinition def = McpToolDefinition.builder()
                .name("test_echo")
                .description("回显测试工具")
                .inputSchema(Map.of("type", "object"))
                .build();

        McpToolHandler echoHandler = new McpToolHandler() {
            @Override
            public McpToolDefinition getDefinition() {
                return def;
            }

            @Override
            public Mono<McpToolCallResult> execute(Map<String, Object> arguments) {
                String msg = arguments.getOrDefault("message", "empty").toString();
                return Mono.just(McpToolCallResult.success("echo: " + msg));
            }
        };

        registry.register(echoHandler);
        assertEquals(1, registry.count());
        assertTrue(registry.containsTool("test_echo"));

        List<McpToolDefinition> tools = registry.listTools();
        assertEquals(1, tools.size());
        assertEquals("test_echo", tools.get(0).getName());

        StepVerifier.create(registry.execute("test_echo", Map.of("message", "hello-mcp")))
                .assertNext(result -> {
                    assertFalse(result.getIsError());
                    assertEquals("echo: hello-mcp", result.getContent().get(0).getText());
                })
                .verifyComplete();

        registry.unregister("test_echo");
        assertEquals(0, registry.count());
        assertFalse(registry.containsTool("test_echo"));

        StepVerifier.create(registry.execute("test_echo", Map.of()))
                .assertNext(result -> assertTrue(result.getIsError()))
                .verifyComplete();
    }

    @Test
    @DisplayName("测试 DynamicToolConfig 声明式配置与 Draft-07 JSON Schema 转换")
    void shouldConvertDynamicToolToSchema() {
        DynamicToolConfig config = DynamicToolConfig.builder()
                .name("macro_news")
                .description("获取最新财经新闻")
                .endpoint("https://news.example.com/api")
                .method("POST")
                .parameters(List.of(
                        new DynamicToolConfig.ParameterConfig("keyword", "string", "关键词", true),
                        new DynamicToolConfig.ParameterConfig("limit", "integer", "获取条数", false)
                ))
                .build();

        McpToolDefinition def = config.toDefinition();
        assertEquals("macro_news", def.getName());
        assertEquals("获取最新财经新闻", def.getDescription());

        Map<String, Object> schema = def.getInputSchema();
        assertNotNull(schema);
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("keyword"));
        assertTrue(properties.containsKey("limit"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("keyword"));
        assertFalse(required.contains("limit"));

        DynamicHttpToolHandler handler = new DynamicHttpToolHandler(config, WebClient.builder());
        assertNotNull(handler.getDefinition());
        assertEquals("macro_news", handler.getDefinition().getName());
    }
}
