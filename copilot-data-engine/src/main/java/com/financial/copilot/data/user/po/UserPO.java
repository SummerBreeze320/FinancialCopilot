package com.financial.copilot.data.user.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financial.copilot.domain.user.entity.User;
import com.financial.copilot.domain.user.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;

/**
 * <h1>系统用户持久化对象 (User PO)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user")
public class UserPO {

    /**
     * 系统用户唯一自增主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户名（唯一登录账号）
     */
    private String username;

    /**
     * BCrypt 加密密码哈希
     */
    private String passwordHash;

    /**
     * 绑定手机号码
     */
    private String mobile;

    /**
     * 绑定电子邮箱
     */
    private String email;

    /**
     * 用户账号状态：ACTIVE(正常活跃), SUSPENDED(已冻结), DELETED(已注销)
     */
    private String status;

    /**
     * 注册创建时间戳
     */
    private LocalDateTime createdAt;

    /**
     * 最后资料更新时间戳
     */
    private LocalDateTime updatedAt;

    public User toDomain() {
        return User.builder()
                .id(id)
                .username(username)
                .passwordHash(passwordHash)
                .mobile(mobile)
                .email(email)
                .status(status != null ? UserStatus.valueOf(status) : UserStatus.ACTIVE)
                .roles(new ArrayList<>())
                .permissions(new ArrayList<>())
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    public static UserPO fromDomain(User domain) {
        if (domain == null) return null;
        return UserPO.builder()
                .id(domain.getId())
                .username(domain.getUsername())
                .passwordHash(domain.getPasswordHash())
                .mobile(domain.getMobile())
                .email(domain.getEmail())
                .status(domain.getStatus() != null ? domain.getStatus().name() : UserStatus.ACTIVE.name())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
