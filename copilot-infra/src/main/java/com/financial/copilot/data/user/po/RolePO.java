package com.financial.copilot.data.user.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financial.copilot.domain.user.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * <h1>系统角色持久化对象 (Role PO)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_role")
public class RolePO {

    /**
     * 自增主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 角色唯一代码（例如 "ROLE_ADMIN", "ROLE_ANALYST"）
     */
    private String roleCode;

    /**
     * 角色名称（例如 "系统管理员", "资深投研分析师"）
     */
    private String roleName;

    /**
     * 角色职权说明
     */
    private String description;

    /**
     * 是否为系统内置角色（内置角色不可删除）
     */
    private Boolean isSystem;

    /**
     * 创建时间戳
     */
    private LocalDateTime createdAt;

    public Role toDomain() {
        return Role.builder()
                .id(id)
                .roleCode(roleCode)
                .roleName(roleName)
                .description(description)
                .isSystem(Boolean.TRUE.equals(isSystem))
                .createdAt(createdAt)
                .build();
    }

    public static RolePO fromDomain(Role domain) {
        if (domain == null) return null;
        return RolePO.builder()
                .id(domain.getId())
                .roleCode(domain.getRoleCode())
                .roleName(domain.getRoleName())
                .description(domain.getDescription())
                .isSystem(domain.isSystem())
                .createdAt(domain.getCreatedAt())
                .build();
    }
}
