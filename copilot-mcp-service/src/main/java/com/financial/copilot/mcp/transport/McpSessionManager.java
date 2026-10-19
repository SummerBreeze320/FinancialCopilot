package com.financial.copilot.mcp.transport;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>MCP SSE 会话管理器</h1>
 * <p>
 * 为每个通过 GET /sse 建立长连接的客户端维护独立的生命周期与消息发射通道。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class McpSessionManager {

    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();

    @Getter
    public static class McpSession {
        private final String sessionId;
        private final Sinks.Many<ServerSentEvent<String>> sink;

        public McpSession(String sessionId) {
            this.sessionId = sessionId;
            this.sink = Sinks.many().multicast().onBackpressureBuffer();
        }

        public Flux<ServerSentEvent<String>> asFlux() {
            return sink.asFlux();
        }

        public void emit(String eventType, String data) {
            sink.tryEmitNext(ServerSentEvent.<String>builder()
                    .event(eventType)
                    .data(data)
                    .build());
        }

        public void complete() {
            sink.tryEmitComplete();
        }
    }

    /**
     * 创建并注册新的 SSE 会话
     *
     * @return 会话对象
     */
    public McpSession createSession() {
        String sessionId = UUID.randomUUID().toString();
        McpSession session = new McpSession(sessionId);
        sessions.put(sessionId, session);
        log.info("[MCP SESSION] 新客户端连接建立: sessionId={}, 当前活跃会话数: {}", sessionId, sessions.size());
        return session;
    }

    /**
     * 获取指定 ID 的会话
     */
    public McpSession getSession(String sessionId) {
        return sessionId != null ? sessions.get(sessionId) : null;
    }

    /**
     * 向指定会话发射 JSON-RPC 消息
     */
    public boolean emitMessage(String sessionId, String jsonMessage) {
        McpSession session = getSession(sessionId);
        if (session != null) {
            session.emit("message", jsonMessage);
            return true;
        }
        return false;
    }

    /**
     * 移除并关闭指定会话
     */
    public void removeSession(String sessionId) {
        if (sessionId != null) {
            McpSession session = sessions.remove(sessionId);
            if (session != null) {
                session.complete();
                log.info("[MCP SESSION] 客户端连接断开释放: sessionId={}, 剩余活跃会话数: {}", sessionId, sessions.size());
            }
        }
    }

    /**
     * 当前活跃会话数
     */
    public int activeSessionCount() {
        return sessions.size();
    }
}
