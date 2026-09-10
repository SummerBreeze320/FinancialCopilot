package com.financial.copilot.controller.user;

import com.financial.copilot.agent.core.security.UserPrincipal;
import com.financial.copilot.agent.core.user.dto.AuthResponse;
import com.financial.copilot.agent.core.user.dto.UserLoginRequest;
import com.financial.copilot.agent.core.user.dto.UserRegisterRequest;
import com.financial.copilot.agent.core.user.service.UserService;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.config.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * <h1>用户开放认证与令牌管理 REST 控制器 (Authentication Controller)</h1>
 * <p>
 * 职责：
 * <ul>
 *   <li>用户账号注册：自动开户并初始化算力钱包赠送 10,000 体验点数；</li>
 *   <li>用户密码登录与双令牌 (Access/Refresh Token) 签发；</li>
 *   <li>长效刷新令牌换发新 Access Token；</li>
 *   <li>获取当前已登录用户的安全主体档案信息。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /**
     * 新用户注册接口（自动赠送 10,000 算力点体验金）
     *
     * @param request 注册载荷
     * @return 认证结果与 JWT 令牌凭证
     */
    @PostMapping("/register")
    public ApiResult<AuthResponse> register(@RequestBody UserRegisterRequest request) {
        log.info("[HTTP-AUTH] 接收用户注册请求: username={}, mobile={}", request.getUsername(), request.getMobile());
        AuthResponse response = userService.register(request);
        return ApiResult.success(response);
    }

    /**
     * 账号密码登录接口
     *
     * @param request 登录请求凭据
     * @return 登录结果与 JWT 令牌凭据
     */
    @PostMapping("/login")
    public ApiResult<AuthResponse> login(@RequestBody UserLoginRequest request) {
        log.info("[HTTP-AUTH] 接收用户登录请求: username={}", request.getUsername());
        AuthResponse response = userService.login(request);
        return ApiResult.success(response);
    }

    /**
     * 刷新访问令牌接口
     *
     * @param refreshToken 请求参数或自定义请求头中的长效刷新令牌
     * @param headerToken  请求头携带的 Refresh Token
     * @return 全新的双令牌响应
     */
    @PostMapping("/refresh-token")
    public ApiResult<AuthResponse> refreshToken(
            @RequestParam(value = "refreshToken", required = false) String refreshToken,
            @RequestHeader(value = "X-Refresh-Token", required = false) String headerToken) {
        String token = refreshToken != null && !refreshToken.isBlank() ? refreshToken : headerToken;
        if (token == null || token.isBlank()) {
            return ApiResult.fail(400, "缺少 refreshToken 刷新凭证");
        }
        AuthResponse response = userService.refreshToken(token.trim());
        return ApiResult.success(response);
    }

    /**
     * 查询当前登录用户的安全身份详情
     *
     * @return 当前认证主体详情
     */
    @GetMapping("/me")
    public Mono<ApiResult<UserPrincipal>> getCurrentUserProfile() {
        return SecurityUtils.getCurrentUser()
                .map(ApiResult::success)
                .defaultIfEmpty(ApiResult.fail(401, "当前未认证或登录凭证已失效"));
    }
}
