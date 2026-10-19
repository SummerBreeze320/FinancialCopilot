package com.financial.copilot.mcp.context;

import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * <h1>MCP 响应式用户会话上下文 (McpUserContext)</h1>
 * <p>
 * 基于 Project Reactor 的 Context 机制，在 WebFlux 管道内部传递经认证的用户 ID，
 * 使 MCP Tool 执行器能够透明获取用户身份，避免将 userId 暴露给大语言模型作为 Tool 参数。
 * </p>
 *
 * @author FinancialCopilot
 */
public final class McpUserContext {

    public static final String CONTEXT_KEY = "MCP_USER_ID";
    public static final Long DEFAULT_FALLBACK_USER_ID = 1L;

    private McpUserContext() {
    }

    /**
     * 从当前响应式上下文获取用户 ID
     *
     * @return 包含用户 ID 的 Mono
     */
    public static Mono<Long> getCurrentUserId() {
        return Mono.deferContextual(ctx -> Mono.just(getUserIdOrDefault(ctx)));
    }

    /**
     * 从 ContextView 中同步提取用户 ID，缺省回退到默认用户
     *
     * @param contextView Reactor 上下文视图
     * @return 用户 ID
     */
    public static Long getUserIdOrDefault(ContextView contextView) {
        if (contextView.hasKey(CONTEXT_KEY)) {
            Object val = contextView.get(CONTEXT_KEY);
            if (val instanceof Long l) {
                return l;
            } else if (val instanceof Number n) {
                return n.longValue();
            } else if (val instanceof String s) {
                try {
                    return Long.parseLong(s.trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return DEFAULT_FALLBACK_USER_ID;
    }
}
