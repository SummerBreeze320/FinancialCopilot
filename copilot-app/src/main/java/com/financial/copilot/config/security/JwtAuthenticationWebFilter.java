package com.financial.copilot.config.security;

import com.financial.copilot.agent.core.security.JwtUtils;
import com.financial.copilot.agent.core.security.UserPrincipal;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * <h1>WebFlux JWT 响应式安全认证过滤器</h1>
 * <p>
 * 职责：拦截进站 HTTP 请求，解析 Authorization 请求头中的 Bearer JWT 令牌。
 * 校验有效性后，构造 {@link UserPrincipal} 与 {@link UsernamePasswordAuthenticationToken}，
 * 注入响应式安全上下文 {@link ReactiveSecurityContextHolder}。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationWebFilter implements WebFilter {

    private final JwtUtils jwtUtils;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return chain.filter(exchange);
        }

        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            return chain.filter(exchange);
        }

        try {
            Claims claims = jwtUtils.parseClaims(token);
            if (claims != null && jwtUtils.validateToken(token)) {
                Long userId = jwtUtils.extractUserId(token);
                String username = jwtUtils.extractUsername(token);
                List<String> roles = jwtUtils.extractRoles(token);

                List<String> permissions = List.of();
                Object p = claims.get("permissions");
                if (p instanceof List<?> pList) {
                    permissions = pList.stream().map(Object::toString).toList();
                }

                UserPrincipal principal = UserPrincipal.builder()
                        .userId(userId)
                        .username(username)
                        .roles(roles)
                        .permissions(permissions)
                        .build();

                Authentication authentication = new UsernamePasswordAuthenticationToken(
                        principal,
                        token,
                        principal.getAuthorities()
                );

                return chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
            }
        } catch (Exception e) {
            log.debug("[SECURITY] JWT 认证过滤解析失败: {}", e.getMessage());
        }

        return chain.filter(exchange);
    }
}
