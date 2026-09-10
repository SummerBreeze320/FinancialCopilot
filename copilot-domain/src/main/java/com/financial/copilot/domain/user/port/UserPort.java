package com.financial.copilot.domain.user.port;

import com.financial.copilot.domain.user.entity.*;

import java.util.List;
import java.util.Optional;

/**
 * <h1>用户体系与 RBAC 仓储持久化端口 (User & RBAC Domain Port)</h1>
 * <p>
 * 职责：遵循六边形架构 / DDD 规范，定义用户、角色、权限、个人资料、实名档案与投资画像的持久化标准。
 * </p>
 *
 * @author FinancialCopilot
 */
public interface UserPort {

    /**
     * 根据主键 ID 检索用户
     *
     * @param id 用户主键
     * @return 用户实体 (若存在)
     */
    Optional<User> findById(Long id);

    /**
     * 根据登录账号检索用户
     *
     * @param username 用户名
     * @return 用户实体 (若存在)
     */
    Optional<User> findByUsername(String username);

    /**
     * 根据手机号检索用户
     *
     * @param mobile 手机号码
     * @return 用户实体 (若存在)
     */
    Optional<User> findByMobile(String mobile);

    /**
     * 根据邮箱检索用户
     *
     * @param email 电子邮箱
     * @return 用户实体 (若存在)
     */
    Optional<User> findByEmail(String email);

    /**
     * 保存或更新用户账号主体
     *
     * @param user 用户实体
     * @return 持久化后的实体
     */
    User saveUser(User user);

    /**
     * 为用户分配指定角色
     *
     * @param userId   用户 ID
     * @param roleCode 角色代码 (如 ROLE_USER)
     */
    void assignRole(Long userId, String roleCode);

    /**
     * 查询指定用户所拥有的全部角色
     *
     * @param userId 用户 ID
     * @return 角色列表
     */
    List<Role> findRolesByUserId(Long userId);

    /**
     * 查询指定角色关联的权限清单
     *
     * @param roleId 角色 ID
     * @return 权限列表
     */
    List<Permission> findPermissionsByRoleId(Long roleId);

    /**
     * 查询指定用户所聚合的全部权限清单
     *
     * @param userId 用户 ID
     * @return 权限列表
     */
    List<Permission> findPermissionsByUserId(Long userId);

    /**
     * 查询用户个人基本资料
     *
     * @param userId 用户 ID
     * @return 个人资料
     */
    Optional<UserProfile> findProfileByUserId(Long userId);

    /**
     * 保存或更新用户个人资料
     *
     * @param profile 个人资料实体
     * @return 保存后的资料
     */
    UserProfile saveProfile(UserProfile profile);

    /**
     * 查询用户实名认证记录
     *
     * @param userId 用户 ID
     * @return 实名档案
     */
    Optional<UserIdentity> findIdentityByUserId(Long userId);

    /**
     * 根据身份证号哈希检索认证记录 (排重校验)
     *
     * @param idCardHash 身份证 SHA-256 哈希
     * @return 匹配的记录
     */
    Optional<UserIdentity> findIdentityByHash(String idCardHash);

    /**
     * 保存或更新实名认证档案
     *
     * @param identity 实名记录
     * @return 保存后的实名记录
     */
    UserIdentity saveIdentity(UserIdentity identity);

    /**
     * 查询用户投资画像与风险偏好
     *
     * @param userId 用户 ID
     * @return 投资画像
     */
    Optional<UserInvestmentProfile> findInvestmentProfileByUserId(Long userId);

    /**
     * 保存或更新用户投资画像
     *
     * @param profile 投资画像实体
     * @return 保存后的画像
     */
    UserInvestmentProfile saveInvestmentProfile(UserInvestmentProfile profile);

    /**
     * 分页查询系统全量用户
     *
     * @param page 页码 (1-based)
     * @param size 每页大小
     * @return 用户列表
     */
    List<User> listUsers(int page, int size);

    /**
     * 分页查询待审核的实名认证列表
     *
     * @param page 页码 (1-based)
     * @param size 每页大小
     * @return 待审实名记录
     */
    List<UserIdentity> listPendingIdentities(int page, int size);
}
