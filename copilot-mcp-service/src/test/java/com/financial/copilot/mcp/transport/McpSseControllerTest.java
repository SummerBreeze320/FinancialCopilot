package com.financial.copilot.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.mcp.config.McpServerProperties;
import com.financial.copilot.mcp.protocol.McpToolCallResult;
import com.financial.copilot.mcp.protocol.McpToolDefinition;
import com.financial.copilot.mcp.registry.McpToolHandler;
import com.financial.copilot.mcp.registry.McpToolRegistry;
import com.financial.copilot.mcp.service.McpServerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>MCP SSE 与 JSON-RPC 全链路端到端集成测试</h1>
 */
class McpSseControllerTest {

    private WebTestClient client;
    private McpToolRegistry registry;
    private McpSessionManager sessionManager;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = new McpToolRegistry();
        sessionManager = new McpSessionManager();
        McpServerProperties properties = new McpServerProperties();
        properties.setName("financial-test-server");
        properties.setVersion("1.0.0-test");

        McpServerService service = new McpServerService(registry, properties, mapper);
        McpSseController controller = new McpSseController(sessionManager, service, mapper);

        client = WebTestClient.bindToController(controller).build();

        // 注册 1 个测试工具
        registry.register(new McpToolHandler() {
            @Override
            public McpToolDefinition getDefinition() {
                return McpToolDefinition.builder()
                        .name("fund_metrics")
                        .description("基金指标测试工具")
                        .inputSchema(Map.of("type", "object"))
                        .build();
            }

            @Override
            public Mono<McpToolCallResult> execute(Map<String, Object> arguments) {
                String code = arguments.getOrDefault("fundCode", "unknown").toString();
                return Mono.just(McpToolCallResult.success("{\"code\":\"" + code + "\",\"return\":15.8}"));
            }
        });
    }

    @Test
    @DisplayName("测试 GET /sse 建立连接并接收首包 endpoint 事件")
    void shouldEstablishSseAndReceiveEndpoint() {
        FluxExchangeResult<ServerSentEvent<String>> result = client.get()
                .uri("/sse")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(new org.springframework.core.ParameterizedTypeReference<ServerSentEvent<String>>() {});

        StepVerifier.create(result.getResponseBody().take(1))
                .assertNext(event -> {
                    assertEquals("endpoint", event.event());
                    assertNotNull(event.data());
                    assertTrue(event.data().startsWith("/messages?sessionId="));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("测试 POST /messages 握手 initialize 请求响应")
    void shouldHandleInitializeMessage() throws Exception {
        String initRequest = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "initialize",
                    "params": {
                        "protocolVersion": "2024-11-05",
                        "capabilities": {}
                    }
                }
                """;

        client.post()
                .uri("/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(initRequest)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    try {
                        JsonNode root = mapper.readTree(body);
                        assertEquals("2.0", root.get("jsonrpc").asText());
                        assertEquals(1, root.get("id").asInt());
                        assertEquals("2024-11-05", root.path("result").path("protocolVersion").asText());
                        assertEquals("financial-test-server", root.path("result").path("serverInfo").path("name").asText());
                    } catch (Exception e) {
                        fail(e);
                    }
                });
    }

    @Test
    @DisplayName("测试 POST /messages 执行 tools/list 获取工具清单")
    void shouldHandleToolsList() throws Exception {
        String listRequest = """
                {
                    "jsonrpc": "2.0",
                    "id": 2,
                    "method": "tools/list"
                }
                """;

        client.post()
                .uri("/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(listRequest)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    try {
                        JsonNode root = mapper.readTree(body);
                        assertEquals(2, root.get("id").asInt());
                        JsonNode tools = root.path("result").path("tools");
                        assertTrue(tools.isArray());
                        assertEquals(1, tools.size());
                        assertEquals("fund_metrics", tools.get(0).path("name").asText());
                    } catch (Exception e) {
                        fail(e);
                    }
                });
    }

    @Test
    @DisplayName("测试 POST /messages 执行 tools/call 调用工具并返回结果")
    void shouldHandleToolsCall() throws Exception {
        String callRequest = """
                {
                    "jsonrpc": "2.0",
                    "id": 3,
                    "method": "tools/call",
                    "params": {
                        "name": "fund_metrics",
                        "arguments": {
                            "fundCode": "005827"
                        }
                    }
                }
                """;

        client.post()
                .uri("/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(callRequest)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    try {
                        JsonNode root = mapper.readTree(body);
                        assertEquals(3, root.get("id").asInt());
                        JsonNode content = root.path("result").path("content");
                        assertTrue(content.isArray());
                        assertEquals("text", content.get(0).path("type").asText());
                        assertTrue(content.get(0).path("text").asText().contains("005827"));
                    } catch (Exception e) {
                        fail(e);
                    }
                });
    }

    @Test
    @DisplayName("测试未知方法返回标准 -32601 错误码")
    void shouldReturnMethodNotFound() throws Exception {
        String unknownRequest = """
                {
                    "jsonrpc": "2.0",
                    "id": 4,
                    "method": "unknown/method"
                }
                """;

        client.post()
                .uri("/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(unknownRequest)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    try {
                        JsonNode root = mapper.readTree(body);
                        assertEquals(4, root.get("id").asInt());
                        assertEquals(-32601, root.path("error").path("code").asInt());
                    } catch (Exception e) {
                        fail(e);
                    }
                });
    }
}
