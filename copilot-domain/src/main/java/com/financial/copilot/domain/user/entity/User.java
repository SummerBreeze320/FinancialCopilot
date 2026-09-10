package com.financial.copilot.domain.user.entity;

import com.financial.copilot.domain.user.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <h1>系统用户核心领域实体 (User Domain Entity)</h1>
 * <p>
 * 职责：封装用户核心账号凭证、多角色分配与启停用风控状态。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User implements Serializable {

    /** 用户唯一主键 ID */
    private Long id;

    /** 唯一登录账号 */
    private String username;

    /** BCrypt 强哈希密码密文 */
    private String passwordHash;

    /** 绑定手机号 */
    private String mobile;

    /** 绑定电子邮箱 */
    private String email;

    /** 账号状态 */
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    /** 关联的角色列表 */
    @Builder.Default
    private List<Role> roles = new ArrayList<>();

    /** 聚合的全量权限点列表 (基于角色派生) */
    @Builder.Default
    private List<Permission> permissions = new ArrayList<>();

    /** 账号注册创建时间 */
    private LocalDateTime createdAt;

    /** 资料最近更新时间 */
    private LocalDateTime updatedAt;

    /**
     * 判断账号是否处于可用激活状态
     *
     * @return true 若为 ACTIVE
     */
    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
