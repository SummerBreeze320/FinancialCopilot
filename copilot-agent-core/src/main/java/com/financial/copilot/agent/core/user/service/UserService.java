package com.financial.copilot.agent.core.user.service;

import com.financial.copilot.agent.core.security.UserPrincipal;
import com.financial.copilot.agent.core.user.dto.*;
import com.financial.copilot.domain.user.entity.User;
import com.financial.copilot.domain.user.entity.UserIdentity;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;
import com.financial.copilot.domain.user.entity.UserProfile;

import java.util.List;

/**
 * <h1>用户认证、金融实名认证与投资画像统一业务契约 (User Service)</h1>
 * <p>
 * 职责：
 * <ul>
 *   <li>用户注册（自动触发钱包开户并赠送初始算力体验金、分配 ROLE_USER、初始化默认画像）；</li>
 *   <li>用户登录认证与 JWT 令牌签发；</li>
 *   <li>个人资料查询与维护；</li>
 *   <li>金融合规实名认证 (KYC) 申请提交与平台审核；</li>
 *   <li>用户投资画像 (Investment Persona) 的评测维护与投研上下文挂载。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
public interface UserService {

    /**
     * 用户账号注册
     *
     * @param request 注册请求载荷
     * @return 注册并登录后的认证凭据与 Token
     */
    AuthResponse register(UserRegisterRequest request);

    /**
     * 账号密码登录
     *
     * @param request 登录请求
     * @return 认证成功后的 Token 及权限信息
     */
    AuthResponse login(UserLoginRequest request);

    /**
     * 刷新访问令牌
     *
     * @param refreshToken 刷新令牌
     * @return 全新的 Token 凭据
     */
    AuthResponse refreshToken(String refreshToken);

    /**
     * 供 Spring Security 调用的主体加载方法
     *
     * @param username 登录账号
     * @return 用户安全主体
     */
    UserPrincipal loadUserByUsername(String username);

    /**
     * 查询用户基础个人资料
     *
     * @param userId 用户 ID
     * @return 个人资料
     */
    UserProfile getProfile(Long userId);

    /**
     * 更新用户个人资料
     *
     * @param userId  用户 ID
     * @param request 更新数据
     * @return 更新后的个人资料
     */
    UserProfile updateProfile(Long userId, UserProfileUpdateRequest request);

    /**
     * 提交金融实名认证申请
     *
     * @param userId  用户 ID
     * @param request 实名证件信息
     * @return 实名审核申请单
     */
    UserIdentity submitIdentityVerification(Long userId, IdentityVerificationRequest request);

    /**
     * 查询指定用户的实名认证状态
     *
     * @param userId 用户 ID
     * @return 实名认证档案
     */
    UserIdentity getIdentity(Long userId);

    /**
     * 管理员审核实名认证申请
     *
     * @param request 审核结果
     * @return 审核后的实名记录
     */
    UserIdentity reviewIdentity(IdentityReviewRequest request);

    /**
     * 查询用户的投资画像与偏好设置
     *
     * @param userId 用户 ID
     * @return 投资偏好画像实体
     */
    UserInvestmentProfile getInvestmentProfile(Long userId);

    /**
     * 评估或更新用户的投资画像与风险偏好
     *
     * @param userId  用户 ID
     * @param request 偏好评测结果
     * @return 更新后的投资画像
     */
    UserInvestmentProfile updateInvestmentProfile(Long userId, InvestmentProfileUpdateRequest request);

    /**
     * 管理员分页查询系统用户列表
     *
     * @param page 页码
     * @param size 每页行数
     * @return 用户列表
     */
    List<User> listUsers(int page, int size);

    /**
     * 管理员分页查询待审核的实名记录列表
     *
     * @param page 页码
     * @param size 每页行数
     * @return 待审列表
     */
    List<UserIdentity> listPendingIdentities(int page, int size);
}
