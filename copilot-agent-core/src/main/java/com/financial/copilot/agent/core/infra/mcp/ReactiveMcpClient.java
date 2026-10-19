package com.financial.copilot.agent.core.infra.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <h1>响应式 MCP 客户端 (ReactiveMcpClient)</h1>
 * <p>
 * 基于 Spring WebFlux {@link WebClient} 与独立运行的 {@code copilot-mcp-service} (端口 8085)
 * 进行非阻塞 JSON-RPC 2.0 (MCP 2024-11-05 规范) 通信。
 * 支持异步工具调度、元数据清单发现以及断路优雅降级。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class ReactiveMcpClient {

    private final McpClientProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public ReactiveMcpClient(McpClientProperties properties, ObjectMapper objectMapper) {
        this(properties, WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build(), objectMapper);
    }

    public ReactiveMcpClient(McpClientProperties properties, WebClient webClient, ObjectMapper objectMapper) {
        this.properties = properties != null ? properties : new McpClientProperties();
        this.webClient = webClient != null ? webClient : WebClient.builder()
                .baseUrl(this.properties.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * 判断当前 MCP 客户端代理是否处于启用状态
     *
     * @return true 若已启用
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 获取 MCP 配置属性
     *
     * @return 属性实例
     */
    public McpClientProperties getProperties() {
        return properties;
    }

    /**
     * 异步非阻塞调用 MCP 工具
     *
     * @param toolName  工具名称
     * @param arguments 入参键值对
     * @return 响应式调用结果 Mono
     */
    public Mono<McpCallResult> callTool(String toolName, Map<String, Object> arguments) {
        if (!properties.isEnabled()) {
            return Mono.just(McpCallResult.error("MCP client is disabled"));
        }

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments != null ? arguments : Collections.emptyMap());

        Map<String, Object> requestPayload = new LinkedHashMap<>();
        requestPayload.put("jsonrpc", "2.0");
        requestPayload.put("id", requestId);
        requestPayload.put("method", "tools/call");
        requestPayload.put("params", params);

        Duration timeout = Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds()));

        return webClient.post()
                .uri("/messages")
                .bodyValue(requestPayload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .map(this::parseToolCallResponse)
                .doOnError(err -> log.warn("[MCP CLIENT] 远程工具调用异常: tool={}, error={}", toolName, err.getMessage()))
                .onErrorResume(err -> Mono.just(McpCallResult.error("MCP Remote Call Error: " + err.getMessage())));
    }

    /**
     * 同步阻塞调用 MCP 工具（支持超时与自动解包）
     *
     * @param toolName  工具名称
     * @param arguments 入参键值对
     * @return 工具返回文本
     */
    public String callToolSync(String toolName, Map<String, Object> arguments) {
        Duration timeout = Duration.ofSeconds(Math.max(2, properties.getTimeoutSeconds() + 2));
        McpCallResult result = callTool(toolName, arguments).block(timeout);
        if (result == null) {
            throw new IllegalStateException("MCP tool [" + toolName + "] execution returned null response");
        }
        if (result.isError()) {
            throw new IllegalStateException("MCP tool [" + toolName + "] failed: " + result.getErrorMessage());
        }
        return result.getContent();
    }

    /**
     * 查询 MCP 服务当前注册并发布的所有工具清单
     *
     * @return 工具清单列表 Mono
     */
    public Mono<List<McpToolInfo>> listTools() {
        if (!properties.isEnabled()) {
            return Mono.just(Collections.emptyList());
        }

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> requestPayload = new LinkedHashMap<>();
        requestPayload.put("jsonrpc", "2.0");
        requestPayload.put("id", requestId);
        requestPayload.put("method", "tools/list");

        Duration timeout = Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds()));

        return webClient.post()
                .uri("/messages")
                .bodyValue(requestPayload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .map(root -> {
                    JsonNode resultNode = root.get("result");
                    if (resultNode == null || !resultNode.has("tools")) {
                        return Collections.<McpToolInfo>emptyList();
                    }
                    JsonNode toolsNode = resultNode.get("tools");
                    if (!toolsNode.isArray()) {
                        return Collections.<McpToolInfo>emptyList();
                    }
                    List<McpToolInfo> list = new ArrayList<>();
                    for (JsonNode item : toolsNode) {
                        try {
                            McpToolInfo info = objectMapper.treeToValue(item, McpToolInfo.class);
                            list.add(info);
                        } catch (Exception e) {
                            log.warn("[MCP CLIENT] 解析工具元数据项失败: {}", e.getMessage());
                        }
                    }
                    return list;
                })
                .onErrorResume(err -> {
                    log.warn("[MCP CLIENT] 获取工具清单失败: {}", err.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    /**
     * 发送 ping 探测 MCP 服务健康状态
     *
     * @return 探测成功 Mono
     */
    public Mono<Boolean> ping() {
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> requestPayload = new LinkedHashMap<>();
        requestPayload.put("jsonrpc", "2.0");
        requestPayload.put("id", requestId);
        requestPayload.put("method", "ping");

        return webClient.post()
                .uri("/messages")
                .bodyValue(requestPayload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(3))
                .map(node -> node.has("result"))
                .onErrorReturn(false);
    }

    private McpCallResult parseToolCallResponse(JsonNode root) {
        if (root.has("error") && !root.get("error").isNull()) {
            JsonNode errorNode = root.get("error");
            String msg = errorNode.has("message") ? errorNode.get("message").asText() : errorNode.toString();
            return McpCallResult.error(msg);
        }

        JsonNode resultNode = root.get("result");
        if (resultNode == null) {
            return McpCallResult.error("Missing result in JSON-RPC response");
        }

        boolean isError = resultNode.has("isError") && resultNode.get("isError").asBoolean();
        JsonNode contentArray = resultNode.get("content");
        StringBuilder sb = new StringBuilder();

        if (contentArray != null && contentArray.isArray()) {
            for (JsonNode contentItem : contentArray) {
                if (contentItem.has("text")) {
                    sb.append(contentItem.get("text").asText());
                }
            }
        }

        String text = sb.toString();
        if (isError) {
            return McpCallResult.error(text.isBlank() ? "Tool reported execution error" : text);
        }
        return McpCallResult.success(text);
    }
}
