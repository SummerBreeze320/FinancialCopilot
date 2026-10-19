package com.financial.copilot.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.mcp.protocol.McpJsonRpcError;
import com.financial.copilot.mcp.protocol.McpJsonRpcRequest;
import com.financial.copilot.mcp.protocol.McpJsonRpcResponse;
import com.financial.copilot.mcp.service.McpServerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * <h1>MCP 标准 SSE 传输协议控制器</h1>
 * <p>
 * 严格实现 Anthropic Model Context Protocol 2024-11-05 SSE Transport 规范：
 * <ul>
 *     <li>GET /sse 或 /api/mcp/sse: 建立双工消息下行通道，发送首包 endpoint 事件；</li>
 *     <li>POST /messages 或 /api/mcp/messages?sessionId=xxx: 接收客户端上行 JSON-RPC 请求并分发处理。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@RestController
public class McpSseController {

    private final McpSessionManager sessionManager;
    private final McpServerService serverService;
    private final ObjectMapper objectMapper;

    public McpSseController(McpSessionManager sessionManager,
                            McpServerService serverService,
                            ObjectMapper objectMapper) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.serverService = Objects.requireNonNull(serverService, "serverService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * SSE 下行长连接入口
     */
    @GetMapping(value = {"/sse", "/api/mcp/sse"}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> establishSse() {
        McpSessionManager.McpSession session = sessionManager.createSession();
        String sessionId = session.getSessionId();

        // 规范要求的首个通知事件：向客户端公布接收消息的 POST 端点
        ServerSentEvent<String> endpointEvent = ServerSentEvent.<String>builder()
                .event("endpoint")
                .data("/messages?sessionId=" + sessionId)
                .build();

        return Flux.concat(
                Mono.just(endpointEvent),
                session.asFlux()
        ).doFinally(signalType -> {
            log.info("[MCP SSE] 连接终止信号: sessionId={}, signal={}", sessionId, signalType);
            sessionManager.removeSession(sessionId);
        });
    }

    /**
     * JSON-RPC 上行消息入口
     */
    @PostMapping(value = {"/messages", "/api/mcp/messages"}, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<McpJsonRpcResponse>> handleMessage(
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestBody String rawJson) {

        log.debug("[MCP MESSAGE] 收到上行消息: sessionId={}, userId={}, body={}", sessionId, userIdHeader, rawJson);

        McpJsonRpcRequest request;
        try {
            request = objectMapper.readValue(rawJson, McpJsonRpcRequest.class);
        } catch (Exception ex) {
            log.warn("[MCP MESSAGE] 请求 JSON 解析失败: {}", ex.getMessage());
            return Mono.just(ResponseEntity.badRequest().body(
                    McpJsonRpcResponse.error(null, McpJsonRpcError.PARSE_ERROR, "Invalid JSON: " + ex.getMessage())
            ));
        }

        Long finalUserId = com.financial.copilot.mcp.context.McpUserContext.DEFAULT_FALLBACK_USER_ID;
        if (userIdHeader != null && !userIdHeader.isBlank()) {
            try {
                finalUserId = Long.parseLong(userIdHeader.trim());
            } catch (NumberFormatException ignored) {
            }
        }

        return serverService.handleRequest(request)
                .map(response -> {
                    // 若存在活跃 SSE 连接，同步通过 SSE 下发响应消息
                    if (sessionId != null) {
                        try {
                            String responseJson = objectMapper.writeValueAsString(response);
                            sessionManager.emitMessage(sessionId, responseJson);
                        } catch (Exception ex) {
                            log.error("[MCP MESSAGE] 序列化 SSE 响应失败: sessionId={}", sessionId, ex);
                        }
                    }
                    return ResponseEntity.ok(response);
                })
                .contextWrite(reactor.util.context.Context.of(com.financial.copilot.mcp.context.McpUserContext.CONTEXT_KEY, finalUserId))
                .defaultIfEmpty(ResponseEntity.status(HttpStatus.ACCEPTED).build());
    }
}
