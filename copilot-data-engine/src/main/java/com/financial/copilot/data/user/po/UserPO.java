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

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String username;

    private String passwordHash;

    private String mobile;

    private String email;

    private String status;

    private LocalDateTime createdAt;

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
