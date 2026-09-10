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

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String roleCode;

    private String roleName;

    private String description;

    private Boolean isSystem;

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
