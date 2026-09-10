package com.financial.copilot.controller.user;

import com.financial.copilot.agent.core.user.dto.IdentityVerificationRequest;
import com.financial.copilot.agent.core.user.dto.InvestmentProfileUpdateRequest;
import com.financial.copilot.agent.core.user.dto.UserProfileUpdateRequest;
import com.financial.copilot.agent.core.user.service.UserService;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.config.security.SecurityUtils;
import com.financial.copilot.domain.user.entity.UserIdentity;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;
import com.financial.copilot.domain.user.entity.UserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * <h1>个人中心、金融实名认证与投资偏好画像 REST 控制器 (User Center Controller)</h1>
 * <p>
 * 职责：
 * <ul>
 *   <li>查询与更新个人基础资料（昵称、头像、职业、地区等）；</li>
 *   <li>金融实名认证 (KYC) 申请提交与实名状态查询；</li>
 *   <li>用户投资画像 (Investment Persona) 的评测偏好查询与更新维护。</li>
 * </ul>
 * 所有接口均动态从响应式 SecurityContext 中安全解析当前登录用户的真实 UID。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 获取当前登录用户的个人基本资料
     *
     * @return 个人资料档案
     */
    @GetMapping("/profile")
    public Mono<ApiResult<UserProfile>> getProfile() {
        return SecurityUtils.getCurrentUserId()
                .map(userService::getProfile)
                .map(ApiResult::success)
                .defaultIfEmpty(ApiResult.fail(401, "请先登录认证"));
    }

    /**
     * 更新当前登录用户的个人基本资料
     *
     * @param request 待更新资料载荷
     * @return 更新后的个人资料
     */
    @PutMapping("/profile")
    public Mono<ApiResult<UserProfile>> updateProfile(@RequestBody UserProfileUpdateRequest request) {
        return SecurityUtils.getCurrentUserId()
                .map(userId -> userService.updateProfile(userId, request))
                .map(ApiResult::success)
                .defaultIfEmpty(ApiResult.fail(401, "请先登录认证"));
    }

    /**
     * 提交金融实名认证 (KYC) 证件申请
     *
     * @param request 实名申请证件与人像载荷
     * @return 审核中的实名认证记录
     */
    @PostMapping("/identity/submit")
    public Mono<ApiResult<UserIdentity>> submitIdentity(@RequestBody IdentityVerificationRequest request) {
        return SecurityUtils.getCurrentUserId()
                .map(userId -> userService.submitIdentityVerification(userId, request))
                .map(ApiResult::success)
                .defaultIfEmpty(ApiResult.fail(401, "请先登录认证"));
    }

    /**
     * 查询当前登录用户的实名认证档案与合规状态
     *
     * @return 实名认证记录
     */
    @GetMapping("/identity")
    public Mono<ApiResult<UserIdentity>> getIdentity() {
        return SecurityUtils.getCurrentUserId()
                .map(userService::getIdentity)
                .map(ApiResult::success)
                .defaultIfEmpty(ApiResult.fail(401, "请先登录认证"));
    }

    /**
     * 查询当前登录用户的投资偏好与风险画像
     *
     * @return 投资偏好画像实体
     */
    @GetMapping("/investment-profile")
    public Mono<ApiResult<UserInvestmentProfile>> getInvestmentProfile() {
        return SecurityUtils.getCurrentUserId()
                .map(userService::getInvestmentProfile)
                .map(ApiResult::success)
                .defaultIfEmpty(ApiResult.fail(401, "请先登录认证"));
    }

    /**
     * 更新或重新评估用户的投资画像偏好
     *
     * @param request 投资画像更新载荷
     * @return 更新后的投资偏好画像
     */
    @PutMapping("/investment-profile")
    public Mono<ApiResult<UserInvestmentProfile>> updateInvestmentProfile(@RequestBody InvestmentProfileUpdateRequest request) {
        return SecurityUtils.getCurrentUserId()
                .map(userId -> userService.updateInvestmentProfile(userId, request))
                .map(ApiResult::success)
                .defaultIfEmpty(ApiResult.fail(401, "请先登录认证"));
    }
}
