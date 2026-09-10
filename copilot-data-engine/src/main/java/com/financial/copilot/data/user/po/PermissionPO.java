package com.financial.copilot.data.user.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financial.copilot.domain.user.entity.Permission;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * <h1>系统权限点持久化对象 (Permission PO)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_permission")
public class PermissionPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String permCode;

    private String permName;

    private String resourceType;

    private String path;

    private String method;

    private LocalDateTime createdAt;

    public Permission toDomain() {
        return Permission.builder()
                .id(id)
                .permCode(permCode)
                .permName(permName)
                .resourceType(resourceType != null ? resourceType : "API")
                .path(path)
                .method(method)
                .createdAt(createdAt)
                .build();
    }

    public static PermissionPO fromDomain(Permission domain) {
        if (domain == null) return null;
        return PermissionPO.builder()
                .id(domain.getId())
                .permCode(domain.getPermCode())
                .permName(domain.getPermName())
                .resourceType(domain.getResourceType())
                .path(domain.getPath())
                .method(domain.getMethod())
                .createdAt(domain.getCreatedAt())
                .build();
    }
}
