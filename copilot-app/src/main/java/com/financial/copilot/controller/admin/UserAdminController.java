package com.financial.copilot.controller.admin;

import com.financial.copilot.agent.core.user.dto.IdentityReviewRequest;
import com.financial.copilot.agent.core.user.service.UserService;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.domain.user.entity.User;
import com.financial.copilot.domain.user.entity.UserIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <h1>运营与管理员专享：用户管理与实名资质审核 REST 控制器 (User Admin Controller)</h1>
 * <p>
 * 职责：
 * <ul>
 *   <li>查询系统全量注册用户档案及状态列表；</li>
 *   <li>查询待处理的金融实名认证 (KYC) 待审工单池；</li>
 *   <li>执行人工实名审批审核（审核通过签发实名认证资质，驳回填报整改原因）。</li>
 * </ul>
 * 权限控制：严格限制仅具备 {@code ROLE_ADMIN} 高级管理员角色的账号方可调用。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/user")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserService userService;

    /**
     * 分页查询系统全量注册用户列表
     *
     * @param page 页码（从 1 起始，默认 1）
     * @param size 每页记录数（默认 20）
     * @return 用户档案列表
     */
    @GetMapping("/list")
    public ApiResult<List<User>> listUsers(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        log.info("[ADMIN-USER] 管理员分页查询用户档案: page={}, size={}", page, size);
        List<User> users = userService.listUsers(page, size);
        return ApiResult.success(users);
    }

    /**
     * 分页查询待审核的金融实名认证申请工单
     *
     * @param page 页码（默认 1）
     * @param size 每页记录数（默认 20）
     * @return 待审批实名申请列表
     */
    @GetMapping("/identity/pending")
    public ApiResult<List<UserIdentity>> listPendingIdentities(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        log.info("[ADMIN-USER] 管理员查询待审核实名认证工单: page={}, size={}", page, size);
        List<UserIdentity> identities = userService.listPendingIdentities(page, size);
        return ApiResult.success(identities);
    }

    /**
     * 管理员审批实名认证申请
     *
     * @param request 审核结果载荷
     * @return 审核生效后的实名记录
     */
    @PostMapping("/identity/review")
    public ApiResult<UserIdentity> reviewIdentity(@RequestBody IdentityReviewRequest request) {
        log.info("[ADMIN-USER] 管理员审批实名申请: userId={}, approved={}, reason={}",
                request.getUserId(), request.getApproved(), request.getRejectReason());
        UserIdentity updated = userService.reviewIdentity(request);
        return ApiResult.success(updated);
    }
}
