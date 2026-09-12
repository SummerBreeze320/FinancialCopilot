package com.financial.copilot.config.security;

import com.financial.copilot.agent.core.security.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Mono;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * <h1>WebFlux 响应式安全上下文工具类 (Security Utils)</h1>
 * <p>
 * 提供从 {@link ReactiveSecurityContextHolder} 异步获取当前登录用户主体的便捷静态辅助方法。
 * </p>
 *
 * @author FinancialCopilot
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * 获取当前登录的用户主体
     *
     * @return 当前 UserPrincipal Mono，未认证或匿名时返回 Mono.empty()
     */
    public static Mono<UserPrincipal> getCurrentUser() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getPrincipal)
                .filter(p -> p instanceof UserPrincipal)
                .cast(UserPrincipal.class);
    }

    /**
     * 获取当前登录用户的 ID
     *
     * @return 当前用户 ID Mono
     */
    public static Mono<Long> getCurrentUserId() {
        return getCurrentUser().map(UserPrincipal::getUserId);
    }

    /**
     * 校验并获取当前已认证用户 ID，防止越权访问
     *
     * @param requestedUserId 目标用户 ID
     * @return 校验后的用户 ID Mono
     */
    public static Mono<Long> requireCurrentUserId(Long requestedUserId) {
        return getCurrentUserId()
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录")))
                .flatMap(uid -> requestedUserId != null && !requestedUserId.equals(uid)
                        ? Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "不能访问其他用户的数据"))
                        : Mono.just(uid));
    }

    /**
     * 生成隔离的用户会话持久化键
     *
     * @param userId    用户 ID
     * @param sessionId 会话唯一标识
     * @return 带散列哈希的安全隔离键
     */
    public static String sessionKey(Long userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话 ID 必须为 1 至 128 个字符");
        }
        try {
            String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((userId + ":" + sessionId).getBytes(StandardCharsets.UTF_8)));
            return userId + ":" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
