package com.financial.copilot.mcp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.mcp.config.McpServerProperties;
import com.financial.copilot.mcp.protocol.McpInitializeResult;
import com.financial.copilot.mcp.protocol.McpJsonRpcError;
import com.financial.copilot.mcp.protocol.McpJsonRpcRequest;
import com.financial.copilot.mcp.protocol.McpJsonRpcResponse;
import com.financial.copilot.mcp.protocol.McpMethod;
import com.financial.copilot.mcp.protocol.McpToolCallRequest;
import com.financial.copilot.mcp.registry.McpToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Map;

/**
 * <h1>MCP 核心协议分发调度服务</h1>
 * <p>
 * 处理来自客户端的 JSON-RPC 2.0 请求，调度 MCP 注册中心与工具执行引擎。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Service
public class McpServerService {

    private final McpToolRegistry registry;
    private final McpServerProperties properties;
    private final ObjectMapper objectMapper;

    public McpServerService(McpToolRegistry registry,
                            McpServerProperties properties,
                            ObjectMapper objectMapper) {
        this.registry = registry;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理 MCP JSON-RPC 请求
     *
     * @param request 请求体
     * @return 响应体 Mono，若为通知请求则返回 Mono.empty()
     */
    public Mono<McpJsonRpcResponse> handleRequest(McpJsonRpcRequest request) {
        if (request == null) {
            return Mono.just(McpJsonRpcResponse.error(null, McpJsonRpcError.INVALID_REQUEST, "请求体不能为空"));
        }

        String method = request.getMethod();
        Object id = request.getId();

        if (method == null || method.isBlank()) {
            return Mono.just(McpJsonRpcResponse.error(id, McpJsonRpcError.INVALID_REQUEST, "缺少 method 字段"));
        }

        log.debug("[MCP DISPATCH] 收到协议请求: method={}, id={}, isNotification={}", method, id, request.isNotification());

        try {
            return switch (method) {
                case McpMethod.INITIALIZE -> handleInitialize(request);
                case McpMethod.NOTIFICATIONS_INITIALIZED -> handleInitializedNotification(request);
                case McpMethod.TOOLS_LIST -> handleToolsList(request);
                case McpMethod.TOOLS_CALL -> handleToolsCall(request);
                case McpMethod.PING -> Mono.just(McpJsonRpcResponse.success(id, Collections.emptyMap()));
                default -> Mono.just(McpJsonRpcResponse.error(id, McpJsonRpcError.methodNotFound(method)));
            };
        } catch (Exception ex) {
            log.error("[MCP DISPATCH] 处理协议请求抛出异常: method={}, id={}, err={}", method, id, ex.getMessage(), ex);
            return Mono.just(McpJsonRpcResponse.error(id, McpJsonRpcError.internalError(ex.getMessage())));
        }
    }

    private Mono<McpJsonRpcResponse> handleInitialize(McpJsonRpcRequest request) {
        McpInitializeResult result = McpInitializeResult.standard(
                properties.getName(),
                properties.getVersion()
        );
        return Mono.just(McpJsonRpcResponse.success(request.getId(), result));
    }

    private Mono<McpJsonRpcResponse> handleInitializedNotification(McpJsonRpcRequest request) {
        log.info("[MCP DISPATCH] 客户端完成初始化握手确认: id={}", request.getId());
        return Mono.empty();
    }

    private Mono<McpJsonRpcResponse> handleToolsList(McpJsonRpcRequest request) {
        var tools = registry.listTools();
        Map<String, Object> result = Map.of("tools", tools);
        return Mono.just(McpJsonRpcResponse.success(request.getId(), result));
    }

    private Mono<McpJsonRpcResponse> handleToolsCall(McpJsonRpcRequest request) {
        JsonNode params = request.getParams();
        if (params == null || !params.isObject()) {
            return Mono.just(McpJsonRpcResponse.error(request.getId(), McpJsonRpcError.invalidParams("tools/call 缺少有效 params 对象")));
        }

        String toolName = params.path("name").asText(null);
        if (toolName == null || toolName.isBlank()) {
            return Mono.just(McpJsonRpcResponse.error(request.getId(), McpJsonRpcError.invalidParams("缺少 tools/call name 字段")));
        }

        Map<String, Object> arguments = Collections.emptyMap();
        JsonNode argsNode = params.get("arguments");
        if (argsNode != null && argsNode.isObject()) {
            arguments = objectMapper.convertValue(argsNode, new TypeReference<Map<String, Object>>() {});
        }

        return registry.execute(toolName, arguments)
                .map(callResult -> McpJsonRpcResponse.success(request.getId(), callResult));
    }
}
