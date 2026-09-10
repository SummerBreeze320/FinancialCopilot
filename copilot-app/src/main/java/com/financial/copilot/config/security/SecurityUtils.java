package com.financial.copilot.config.security;

import com.financial.copilot.agent.core.security.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Mono;

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
}
