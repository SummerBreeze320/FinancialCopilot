package com.financial.copilot.agent.core.infra.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReactiveMcpClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("MCP 客户端未启用时调用工具应立即返回错误")
    void callToolWhenDisabledReturnsError() {
        McpClientProperties properties = new McpClientProperties();
        properties.setEnabled(false);

        ReactiveMcpClient client = new ReactiveMcpClient(properties, WebClient.builder().build(), mapper);

        StepVerifier.create(client.callTool("fund_metrics", Map.of("fundCode", "000001")))
                .assertNext(result -> {
                    assertThat(result.isError()).isTrue();
                    assertThat(result.getErrorMessage()).contains("disabled");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("成功调用工具并解析 MCP 2024-11-05 标准文本内容")
    void callToolSuccessParsesTextContent() {
        McpClientProperties properties = new McpClientProperties();
        properties.setEnabled(true);
        properties.setTimeoutSeconds(5);

        String mockResponseBody = """
                {
                  "jsonrpc": "2.0",
                  "id": "test-req",
                  "result": {
                    "content": [
                      {
                        "type": "text",
                        "text": "{\\"fundCode\\":\\"000001\\",\\"sharpe3Y\\":1.85}"
                      }
                    ],
                    "isError": false
                  }
                }
                """;

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(mockResponseBody)
                        .build()))
                .build();

        ReactiveMcpClient client = new ReactiveMcpClient(properties, webClient, mapper);

        StepVerifier.create(client.callTool("fund_metrics", Map.of("fundCode", "000001")))
                .assertNext(result -> {
                    assertThat(result.isError()).isFalse();
                    assertThat(result.getContent()).contains("000001");
                    assertThat(result.getContent()).contains("1.85");
                })
                .verifyComplete();

        String syncResult = client.callToolSync("fund_metrics", Map.of("fundCode", "000001"));
        assertThat(syncResult).contains("1.85");
    }

    @Test
    @DisplayName("服务端返回 JSON-RPC error 时应正确转换为失败结果")
    void callToolHandlesJsonRpcError() {
        McpClientProperties properties = new McpClientProperties();
        properties.setEnabled(true);

        String errorResponseBody = """
                {
                  "jsonrpc": "2.0",
                  "id": "test-err",
                  "error": {
                    "code": -32602,
                    "message": "Invalid params: fundCode is required"
                  }
                }
                """;

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(errorResponseBody)
                        .build()))
                .build();

        ReactiveMcpClient client = new ReactiveMcpClient(properties, webClient, mapper);

        StepVerifier.create(client.callTool("fund_metrics", Map.of()))
                .assertNext(result -> {
                    assertThat(result.isError()).isTrue();
                    assertThat(result.getErrorMessage()).contains("Invalid params");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("成功拉取 tools/list 工具清单并正确反序列化")
    void listToolsParsesSuccessfully() {
        McpClientProperties properties = new McpClientProperties();
        properties.setEnabled(true);

        String listResponseBody = """
                {
                  "jsonrpc": "2.0",
                  "id": "list-req",
                  "result": {
                    "tools": [
                      {
                        "name": "fund_metrics",
                        "description": "查询基金收益风险量化指标",
                        "inputSchema": {
                          "type": "object",
                          "properties": {
                            "fundCode": { "type": "string" }
                          }
                        }
                      },
                      {
                        "name": "recognize_entities",
                        "description": "金融实体识别",
                        "inputSchema": {
                          "type": "object"
                        }
                      }
                    ]
                  }
                }
                """;

        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(listResponseBody)
                        .build()))
                .build();

        ReactiveMcpClient client = new ReactiveMcpClient(properties, webClient, mapper);

        StepVerifier.create(client.listTools())
                .assertNext(tools -> {
                    assertThat(tools).hasSize(2);
                    assertThat(tools.get(0).getName()).isEqualTo("fund_metrics");
                    assertThat(tools.get(1).getName()).isEqualTo("recognize_entities");
                })
                .verifyComplete();
    }
}
