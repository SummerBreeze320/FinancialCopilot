package com.financial.copilot.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>MCP 协议序列化与反序列化测试</h1>
 */
class McpProtocolSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("测试 JSON-RPC 2.0 请求对象正确解析与通知识别")
    void shouldParseJsonRpcRequest() throws Exception {
        String json = """
                {
                    "jsonrpc": "2.0",
                    "id": "req-12345",
                    "method": "tools/call",
                    "params": {
                        "name": "fund_metrics",
                        "arguments": {
                            "fundCode": "005827"
                        }
                    }
                }
                """;

        McpJsonRpcRequest request = mapper.readValue(json, McpJsonRpcRequest.class);
        assertEquals("2.0", request.getJsonrpc());
        assertEquals("req-12345", request.getId());
        assertEquals("tools/call", request.getMethod());
        assertFalse(request.isNotification());

        JsonNode params = request.getParams();
        assertNotNull(params);
        assertEquals("fund_metrics", params.get("name").asText());
    }

    @Test
    @DisplayName("测试 Notification 通知类型请求（无 ID）正确判定")
    void shouldRecognizeNotification() throws Exception {
        String json = """
                {
                    "jsonrpc": "2.0",
                    "method": "notifications/initialized"
                }
                """;

        McpJsonRpcRequest request = mapper.readValue(json, McpJsonRpcRequest.class);
        assertEquals("notifications/initialized", request.getMethod());
        assertNull(request.getId());
        assertTrue(request.isNotification());
    }

    @Test
    @DisplayName("测试 Initialize 握手响应序列化")
    void shouldSerializeInitializeResult() throws Exception {
        McpInitializeResult initResult = McpInitializeResult.standard("financial-mcp-server", "1.0.0");
        McpJsonRpcResponse response = McpJsonRpcResponse.success(1, initResult);

        String json = mapper.writeValueAsString(response);
        JsonNode root = mapper.readTree(json);

        assertEquals("2.0", root.get("jsonrpc").asText());
        assertEquals(1, root.get("id").asInt());
        assertEquals("2024-11-05", root.path("result").path("protocolVersion").asText());
        assertEquals("financial-mcp-server", root.path("result").path("serverInfo").path("name").asText());
        assertTrue(root.path("result").path("capabilities").path("tools").path("listChanged").asBoolean());
    }

    @Test
    @DisplayName("测试 ToolDefinition 与 ToolCallResult 序列化格式")
    void shouldSerializeToolsAndCallResult() throws Exception {
        McpToolDefinition toolDef = McpToolDefinition.builder()
                .name("fund_metrics")
                .description("获取单只基金权威量化指标")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("fundCode", Map.of("type", "string", "description", "6位基金代码")),
                        "required", List.of("fundCode")
                ))
                .build();

        String toolJson = mapper.writeValueAsString(toolDef);
        JsonNode toolNode = mapper.readTree(toolJson);
        assertEquals("fund_metrics", toolNode.get("name").asText());
        assertTrue(toolNode.has("inputSchema"));

        McpToolCallResult callResult = McpToolCallResult.success("{\"annualizedReturn\": 18.5}");
        String resultJson = mapper.writeValueAsString(callResult);
        JsonNode resultNode = mapper.readTree(resultJson);
        assertEquals("text", resultNode.path("content").get(0).path("type").asText());
        assertEquals("{\"annualizedReturn\": 18.5}", resultNode.path("content").get(0).path("text").asText());
        assertFalse(resultNode.path("isError").asBoolean());
    }

    @Test
    @DisplayName("测试 JSON-RPC 标准错误响应序列化")
    void shouldSerializeErrorResponse() throws Exception {
        McpJsonRpcResponse errResponse = McpJsonRpcResponse.error(
                "req-99", McpJsonRpcError.methodNotFound("tools/unknown")
        );

        String json = mapper.writeValueAsString(errResponse);
        JsonNode root = mapper.readTree(json);

        assertEquals("req-99", root.get("id").asText());
        assertEquals(-32601, root.path("error").path("code").asInt());
        assertTrue(root.path("error").path("message").asText().contains("Method not found"));
        assertNull(root.get("result"));
    }
}
