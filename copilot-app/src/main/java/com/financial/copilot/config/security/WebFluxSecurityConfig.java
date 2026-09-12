package com.financial.copilot.config.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authorization.HttpStatusServerAccessDeniedHandler;

/**
 * <h1>WebFlux 响应式安全与 RBAC 权限控制配置</h1>
 * <p>
 * 职责：
 * <ul>
 *   <li>开启响应式方法级安全注解支持 (如 {@code @PreAuthorize("hasRole('ADMIN')")})；</li>
 *   <li>配置无状态 JWT 过滤器链，禁用 CSRF、Session 与默认登录表单；</li>
 *   <li>放行公共鉴权与健康检查路由，对其余 API 实施权限拦截与 401/403 标准响应；</li>
 *   <li>注入统一 BCryptPasswordEncoder 密码哈希加密器。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
@RequiredArgsConstructor
public class WebFluxSecurityConfig {

    private final JwtAuthenticationWebFilter jwtAuthenticationWebFilter;

    /**
     * 注册标准 BCrypt 密码加密器 Bean
     *
     * @return PasswordEncoder 实例
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 构建 WebFlux 响应式安全过滤器链
     *
     * @param http ServerHttpSecurity 安全配置器
     * @return 过滤链实例
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .exceptionHandling(spec -> spec
                        .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(new HttpStatusServerAccessDeniedHandler(HttpStatus.FORBIDDEN))
                )
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.POST, "/api/v1/billing/alipay/notify").permitAll()
                        // 放行预检跨域请求
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        // 放行认证鉴权路由
                        .pathMatchers("/api/v1/auth/**").permitAll()
                        // 放行健康检查与公共路由
                        .pathMatchers("/api/v1/research/health").permitAll()
                        // 放行文档与静态资源
                        .pathMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/webjars/**", "/favicon.ico").permitAll()
                        // 管理端路由需要 ADMIN 角色
                        .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // 其余所有请求均要求认证
                        .anyExchange().authenticated()
                )
                .addFilterAt(jwtAuthenticationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
