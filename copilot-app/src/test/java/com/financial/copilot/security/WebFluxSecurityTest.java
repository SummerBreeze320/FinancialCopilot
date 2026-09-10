package com.financial.copilot.security;

import com.financial.copilot.agent.core.security.JwtUtils;
import com.financial.copilot.agent.core.security.UserPrincipal;
import com.financial.copilot.config.security.JwtAuthenticationWebFilter;
import com.financial.copilot.config.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>WebFlux 响应式安全与 JWT 过滤器单元测试 (WebFlux Security Filter Test)</h1>
 *
 * @author FinancialCopilot
 */
class WebFluxSecurityTest {

    private JwtUtils jwtUtils;
    private JwtAuthenticationWebFilter filter;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils("SecretKeyForTestAntigravityFinancialCopilot2026SecureKey!", 7200000, 604800000);
        filter = new JwtAuthenticationWebFilter(jwtUtils);
    }

    @Test
    @DisplayName("验证有效 JWT 令牌成功被过滤器拦截解析并注入 ReactiveSecurityContextHolder")
    void testValidJwtAuthenticationFilter() {
        UserPrincipal principal = UserPrincipal.builder()
                .userId(8888L)
                .username("quant_trader")
                .roles(List.of("ROLE_USER", "ROLE_ANALYST"))
                .permissions(List.of("research:view", "fund:screen"))
                .build();

        String token = jwtUtils.generateAccessToken(principal);

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/user/profile")
                .header("Authorization", "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean verified = new AtomicBoolean(false);

        WebFilterChain chain = ex -> ReactiveSecurityContextHolder.getContext()
                .doOnNext(secContext -> {
                    assertNotNull(secContext.getAuthentication());
                    assertTrue(secContext.getAuthentication().isAuthenticated());
                    Object p = secContext.getAuthentication().getPrincipal();
                    assertTrue(p instanceof UserPrincipal);
                    UserPrincipal up = (UserPrincipal) p;
                    assertEquals(8888L, up.getUserId());
                    assertEquals("quant_trader", up.getUsername());
                    assertTrue(up.getRoles().contains("ROLE_USER"));
                    assertTrue(up.getRoles().contains("ROLE_ANALYST"));
                    verified.set(true);
                })
                .then();

        filter.filter(exchange, chain).block();

        assertTrue(verified.get(), "应成功在下游链条中提取到有效安全上下文");
    }

    @Test
    @DisplayName("验证无 Token 或非 Bearer 请求放行且不注入认证信息")
    void testNoTokenRequestPassesWithoutAuthentication() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/research/health")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);

        WebFilterChain chain = ex -> ReactiveSecurityContextHolder.getContext()
                .hasElement()
                .doOnNext(hasContext -> {
                    assertFalse(hasContext, "无 Token 请求不应存在安全上下文");
                    executed.set(true);
                })
                .then();

        filter.filter(exchange, chain).block();

        assertTrue(executed.get());
    }

    @Test
    @DisplayName("验证非法篡改的 Token 被忽略放行，下游上下文保持未认证")
    void testInvalidTokenIgnoredSafely() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/user/profile")
                .header("Authorization", "Bearer invalid.malformed.token123")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean executed = new AtomicBoolean(false);

        WebFilterChain chain = ex -> ReactiveSecurityContextHolder.getContext()
                .hasElement()
                .doOnNext(hasContext -> {
                    assertFalse(hasContext, "非法 Token 不应注入安全上下文");
                    executed.set(true);
                })
                .then();

        filter.filter(exchange, chain).block();

        assertTrue(executed.get());
    }

    @Test
    @DisplayName("验证 SecurityUtils 异步提取当前认证用户 ID")
    void testSecurityUtilsExtractUserId() {
        UserPrincipal principal = UserPrincipal.builder()
                .userId(9999L)
                .username("admin_boss")
                .roles(List.of("ROLE_ADMIN"))
                .build();

        String token = jwtUtils.generateAccessToken(principal);

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/admin/user/list")
                .header("Authorization", "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean verified = new AtomicBoolean(false);

        WebFilterChain chain = ex -> SecurityUtils.getCurrentUserId()
                .doOnNext(uid -> {
                    assertEquals(9999L, uid);
                    verified.set(true);
                })
                .then();

        filter.filter(exchange, chain).block();

        assertTrue(verified.get());
    }
}
